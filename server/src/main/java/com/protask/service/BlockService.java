package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.BlockPatchRequest;
import com.protask.dto.BlockRequest;
import com.protask.dto.BlockVO;
import com.protask.entity.Block;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 块服务：统一块模型(card/view/text/comment)的 CRUD + 权限校验。
 * 评论创建仅需 COMMENT_PROJECT_CARDS，其余块操作需 MANAGE_PROJECT_CARDS；
 * 删除他人评论需 DELETE_OTHERS_COMMENTS。
 */
@Service
@RequiredArgsConstructor
public class BlockService {

    private final BlockMapper blockMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;
    private final AttachmentService attachmentService;
    private final NotificationService notificationService;

    /**
     * 查询项目下的块列表（可按类型过滤），需查看权限。
     */
    public List<BlockVO> listBlocks(Long userId, Long projectId, String type) {
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        LambdaQueryWrapper<Block> wrapper = new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .isNull(Block::getDeleteAt)
                .orderByAsc(Block::getCreateAt);
        if (type != null && !type.isBlank()) {
            wrapper.eq(Block::getType, type);
        }
        List<Block> blocks = blockMapper.selectList(wrapper);
        return toVOs(blocks);
    }

    /**
     * 创建块。comment 类型需评论权限，其余需卡片管理权限。
     * 评论携带 @提及成员时同事务写入站内通知。
     */
    @Transactional(rollbackFor = Exception.class)
    public BlockVO createBlock(Long userId, Long projectId, BlockRequest request) {
        ProjectPermission required = BlockTypes.COMMENT.equals(request.getType())
                ? ProjectPermission.COMMENT_PROJECT_CARDS
                : ProjectPermission.MANAGE_PROJECT_CARDS;
        permissionService.checkPermission(userId, projectId, required);
        validateParent(projectId, request.getParentId());

        Block block = new Block();
        block.setProjectId(projectId);
        block.setParentId(request.getParentId());
        block.setSchemaVer(1L);
        block.setType(request.getType());
        block.setTitle(request.getTitle() != null ? request.getTitle() : "");
        block.setFields(request.getFields() != null ? request.getFields() : new HashMap<>());
        block.setCreatedBy(userId);
        block.setModifiedBy(userId);
        blockMapper.insert(block);
        if (BlockTypes.COMMENT.equals(request.getType())
                && request.getMentionUserIds() != null && !request.getMentionUserIds().isEmpty()) {
            notificationService.notifyMentions(userId, projectId, request.getParentId(),
                    block.getId(), block.getTitle(), request.getMentionUserIds());
        }
        return toVO(block);
    }

    /**
     * 局部更新块（合并 updatedFields、移除 deletedFields），需卡片管理权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public BlockVO patchBlock(Long userId, Long blockId, BlockPatchRequest request) {
        Block block = getBlock(blockId);
        permissionService.checkPermission(userId, block.getProjectId(),
                ProjectPermission.MANAGE_PROJECT_CARDS);

        if (request.getParentId() != null) {
            validateParent(block.getProjectId(), request.getParentId());
            block.setParentId(request.getParentId());
        }
        if (request.getTitle() != null) {
            block.setTitle(request.getTitle());
        }
        Map<String, Object> fields = block.getFields() != null
                ? new HashMap<>(block.getFields())
                : new HashMap<>();
        if (request.getUpdatedFields() != null) {
            fields.putAll(request.getUpdatedFields());
        }
        if (request.getDeletedFields() != null) {
            request.getDeletedFields().forEach(fields::remove);
        }
        block.setFields(fields);
        block.setModifiedBy(userId);
        blockMapper.updateById(block);
        return toVO(block);
    }

    /**
     * 软删除块。删除他人评论需 DELETE_OTHERS_COMMENTS，
     * 删除自己的评论需评论权限，其余块需卡片管理权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBlock(Long userId, Long blockId) {
        Block block = getBlock(blockId);
        ProjectPermission required;
        if (BlockTypes.COMMENT.equals(block.getType()) && !userId.equals(block.getCreatedBy())) {
            required = ProjectPermission.DELETE_OTHERS_COMMENTS;
        } else if (BlockTypes.COMMENT.equals(block.getType())) {
            required = ProjectPermission.COMMENT_PROJECT_CARDS;
        } else {
            required = ProjectPermission.MANAGE_PROJECT_CARDS;
        }
        permissionService.checkPermission(userId, block.getProjectId(), required);
        block.setDeleteAt(LocalDateTime.now());
        block.setModifiedBy(userId);
        blockMapper.updateById(block);
    }

    /**
     * 从项目下全部卡片的 fields.properties 中移除指定属性值（删除属性定义时级联调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void removePropertyFromCards(Long projectId, List<String> propertyIds, Long userId) {
        if (propertyIds == null || propertyIds.isEmpty()) {
            return;
        }
        List<Block> cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt));
        for (Block card : cards) {
            Map<String, Object> fields = card.getFields();
            if (fields == null || !(fields.get("properties") instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) fields.get("properties");
            boolean changed = propertyIds.stream().anyMatch(properties::containsKey);
            if (!changed) {
                continue;
            }
            propertyIds.forEach(properties::remove);
            Map<String, Object> newFields = new HashMap<>(fields);
            newFields.put("properties", properties);
            card.setFields(newFields);
            card.setModifiedBy(userId);
            blockMapper.updateById(card);
        }
    }

    /**
     * 软删除项目下的全部块（删除项目时级联调用），并级联物理清理项目附件。
     */
    @Transactional(rollbackFor = Exception.class)
    public void softDeleteByProject(Long projectId, Long userId) {
        // 级联清理项目下全部附件（物理删除文件与记录），避免软删除后遗留孤儿附件
        attachmentService.deleteByProject(projectId);
        List<Block> blocks = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .isNull(Block::getDeleteAt));
        LocalDateTime now = LocalDateTime.now();
        for (Block block : blocks) {
            block.setDeleteAt(now);
            block.setModifiedBy(userId);
            blockMapper.updateById(block);
        }
    }

    /**
     * 复制源项目的全部块到新项目（从模板创建/复制项目时调用）：
     * 逐个插入并建立旧ID→新ID映射，最后重建 parentId 父子关系。
     *
     * @return 旧块ID → 新块ID 映射（无块时为空 Map）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, Long> copyBlocksFromProject(Long sourceProjectId, Long newProjectId, Long userId) {
        List<Block> blocks = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, sourceProjectId)
                .isNull(Block::getDeleteAt));
        if (blocks.isEmpty()) {
            return new HashMap<>();
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Long> idMapping = new HashMap<>();
        List<Block> copies = new ArrayList<>();
        for (Block block : blocks) {
            Block copy = new Block();
            copy.setProjectId(newProjectId);
            copy.setSchemaVer(block.getSchemaVer());
            copy.setType(block.getType());
            copy.setTitle(block.getTitle());
            copy.setFields(block.getFields() != null ? new HashMap<>(block.getFields()) : new HashMap<>());
            copy.setCreatedBy(userId);
            copy.setModifiedBy(userId);
            copy.setCreateAt(now);
            copy.setUpdateAt(now);
            blockMapper.insert(copy);
            idMapping.put(block.getId(), copy.getId());
            copies.add(copy);
        }
        // 重建父子关系：旧 parentId 映射为新副本 id
        for (int i = 0; i < blocks.size(); i++) {
            Long oldParentId = blocks.get(i).getParentId();
            if (oldParentId == null) {
                continue;
            }
            Long newParentId = idMapping.get(oldParentId);
            if (newParentId != null && !newParentId.equals(copies.get(i).getParentId())) {
                copies.get(i).setParentId(newParentId);
                copies.get(i).setModifiedBy(userId);
                blockMapper.updateById(copies.get(i));
            }
        }
        return idMapping;
    }

    /**
     * 查询项目下的 card 块（模板库预览用，不做权限校验，仅模板服务内部调用）。
     */
    public List<Block> listCards(Long projectId) {
        return blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt)
                .orderByAsc(Block::getCreateAt));
    }

    private Block getBlock(Long blockId) {
        Block block = blockMapper.selectById(blockId);
        if (block == null || block.getDeleteAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "block.notFound");
        }
        return block;
    }

    /** 校验父块：必须存在、未删除且与当前块属于同一项目，避免跨项目脏引用 */
    private void validateParent(Long projectId, Long parentId) {
        if (parentId == null) {
            return;
        }
        Block parent = blockMapper.selectById(parentId);
        if (parent == null || parent.getDeleteAt() != null || !projectId.equals(parent.getProjectId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "block.parentInvalid");
        }
    }

    private List<BlockVO> toVOs(List<Block> blocks) {
        Set<Long> userIds = new HashSet<>();
        blocks.forEach(b -> {
            userIds.add(b.getCreatedBy());
            userIds.add(b.getModifiedBy());
        });
        Map<Long, String> nicknames = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
        return blocks.stream().map(b -> toVO(b, nicknames)).collect(Collectors.toList());
    }

    private BlockVO toVO(Block block) {
        Map<Long, String> nicknames = userMapper.selectBatchIds(
                        Set.copyOf(List.of(block.getCreatedBy(), block.getModifiedBy()))).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
        return toVO(block, nicknames);
    }

    private BlockVO toVO(Block block, Map<Long, String> nicknames) {
        BlockVO vo = new BlockVO();
        BeanUtils.copyProperties(block, vo);
        vo.setCreatedByName(nicknames.get(block.getCreatedBy()));
        vo.setModifiedByName(nicknames.get(block.getModifiedBy()));
        return vo;
    }
}
