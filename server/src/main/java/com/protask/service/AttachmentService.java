package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.AttachmentVO;
import com.protask.entity.Attachment;
import com.protask.entity.Block;
import com.protask.entity.User;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 任务附件服务：图片附件存 uploads 目录（公开静态访问，供 Markdown 引用与缩略图），
 * 非图片附件存私有目录（不做静态映射，仅通过鉴权下载接口访问）；
 * 删除附件时物理删除文件并同步移除任务描述中的 Markdown 引用。
 * 读需 VIEW_PROJECT，写需 MANAGE_PROJECT_CARDS。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    /** 允许的扩展名白名单：图片 + 文档/压缩包（防御恶意文件） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "zip", "rar", "7z");

    /** 图片扩展名子集：需公开访问（Markdown 引用与缩略图无法携带 token） */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    /** 存储文件名格式校验（防路径穿越） */
    private static final Pattern STORED_NAME_PATTERN = Pattern.compile("[0-9a-f]{32}\\.("
            + String.join("|", ALLOWED_EXTENSIONS) + ")");

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AttachmentMapper attachmentMapper;
    private final BlockMapper blockMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;

    @Value("${protask.upload-dir:uploads}")
    private String uploadDir;

    /** 非图片附件私有存储目录（不做静态映射，仅鉴权下载） */
    @Value("${protask.attachment-dir:uploads-attachments}")
    private String attachmentDir;

    /**
     * 启动时迁移历史数据：早期非图片附件与图片混存在公开目录，
     * 将其移入私有目录后不再可匹名访问（迁移失败不影响启动）。
     */
    @PostConstruct
    void migrateLegacyAttachmentFiles() {
        try {
            Path privateDir = attachmentDirPath();
            Path publicDir = uploadDirPath();
            int moved = 0;
            for (Attachment attachment : attachmentMapper.selectList(null)) {
                String storedName = attachment.getStoredName();
                if (storedName == null || IMAGE_EXTENSIONS.contains(extensionOf(storedName))) {
                    continue;
                }
                Path source = publicDir.resolve(storedName);
                Path target = privateDir.resolve(storedName);
                if (!Files.exists(source) || Files.exists(target)) {
                    continue;
                }
                Files.createDirectories(privateDir);
                Files.move(source, target);
                moved++;
            }
            if (moved > 0) {
                log.info("已将 {} 个历史非图片附件迁移至私有目录", moved);
            }
        } catch (Exception e) {
            log.warn("历史附件文件迁移失败（不影响启动）", e);
        }
    }

    /**
     * 上传附件到任务（需卡片管理权限）：图片进公开目录，非图片进私有目录。
     */
    public AttachmentVO upload(Long userId, Long taskId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "upload.fileEmpty");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "attachment.typeNotAllowed");
        }
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.MANAGE_PROJECT_CARDS);

        boolean image = IMAGE_EXTENSIONS.contains(extension);
        Path dir = image ? uploadDirPath() : attachmentDirPath();
        Files.createDirectories(dir);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        file.transferTo(dir.resolve(storedName).toFile());

        Attachment attachment = new Attachment();
        attachment.setTaskId(taskId);
        attachment.setOriginalName(file.getOriginalFilename());
        attachment.setStoredName(storedName);
        attachment.setContentType(file.getContentType());
        attachment.setSize(file.getSize());
        attachment.setCreatedBy(userId);
        try {
            attachmentMapper.insert(attachment);
        } catch (RuntimeException e) {
            // 记录写入失败时清理已落盘的文件，避免产生无主孤儿文件
            try {
                Files.deleteIfExists(dir.resolve(storedName));
            } catch (IOException ignored) {
                // 清理失败仅丢失本次文件，不阻断异常上抛
            }
            throw e;
        }
        return toVO(attachment);
    }

    /**
     * 查询任务附件列表（需查看权限），按上传时间倒序。
     */
    public List<AttachmentVO> list(Long userId, Long taskId) {
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.VIEW_PROJECT);
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getTaskId, taskId)
                .orderByDesc(Attachment::getCreateAt));
        List<AttachmentVO> vos = new ArrayList<>();
        for (Attachment attachment : attachments) {
            vos.add(toVO(attachment));
        }
        return vos;
    }

    /**
     * 删除附件（需卡片管理权限）：物理删除文件与记录，
     * 若任务描述引用该附件图片则同步移除对应 Markdown 文本。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long taskId, Long attachmentId) {
        Attachment attachment = requireAttachment(taskId, attachmentId);
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.MANAGE_PROJECT_CARDS);

        if (!STORED_NAME_PATTERN.matcher(attachment.getStoredName()).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "attachment.invalidFilename");
        }
        // 删除磁盘文件（不存在则忽略，保证删除幂等；私有/公开目录均覆盖）
        try {
            Files.deleteIfExists(resolveFilePath(attachment));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "upload.deleteFailed");
        }
        attachmentMapper.deleteById(attachmentId);
        removeDescriptionReference(card, attachment.getStoredName(), userId);
    }

    /**
     * 级联删除项目下全部任务(card)的附件：物理删除磁盘文件与记录。
     * 项目/模板软删除时调用，避免软删除后遗留孤儿附件；
     * 单个文件删除失败仅记录日志，不阻断项目删除主流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByProject(Long projectId) {
        List<Block> cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt));
        if (cards.isEmpty()) {
            return;
        }
        List<Long> cardIds = new ArrayList<>();
        for (Block card : cards) {
            cardIds.add(card.getId());
        }
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, cardIds));
        for (Attachment attachment : attachments) {
            if (!STORED_NAME_PATTERN.matcher(attachment.getStoredName()).matches()) {
                log.warn("项目删除级联清理：跳过异常文件名附件 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName());
                continue;
            }
            try {
                Files.deleteIfExists(resolveFilePath(attachment));
            } catch (IOException e) {
                log.warn("项目删除级联清理：删除附件文件失败 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName(), e);
            }
            attachmentMapper.deleteById(attachment.getId());
        }
    }

    /**
     * 物理清理项目下全部块的附件（含已软删块）：删除磁盘文件与记录。
     * 管理后台物理清理已软删项目时调用，返回清理的附件记录数与磁盘文件数。
     */
    public AttachmentCleanupResult physicallyDeleteByProject(Long projectId) {
        List<Long> blockIds = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                        .eq(Block::getProjectId, projectId))
                .stream().map(Block::getId).toList();
        if (blockIds.isEmpty()) {
            return new AttachmentCleanupResult(0, 0);
        }
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, blockIds));
        int records = 0;
        int files = 0;
        for (Attachment attachment : attachments) {
            if (!STORED_NAME_PATTERN.matcher(attachment.getStoredName()).matches()) {
                log.warn("项目物理清理：跳过异常文件名附件 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName());
                continue;
            }
            try {
                Files.deleteIfExists(resolveFilePath(attachment));
                files++;
            } catch (IOException e) {
                log.warn("项目物理清理：删除附件文件失败 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName(), e);
            }
            attachmentMapper.deleteById(attachment.getId());
            records++;
        }
        return new AttachmentCleanupResult(records, files);
    }

    /**
     * 统计项目下全部块的附件记录数（含已软删块），供管理后台清理前预览。
     */
    public long countByProject(Long projectId) {
        List<Long> blockIds = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                        .eq(Block::getProjectId, projectId))
                .stream().map(Block::getId).toList();
        if (blockIds.isEmpty()) {
            return 0;
        }
        return attachmentMapper.selectCount(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, blockIds));
    }

    /**
     * 统计项目下全部块的附件磁盘文件数（含已软删块），供管理后台清理前预览。
     * 与物理清理时实际删除的文件数口径一致：仅统计存储文件名格式合法的附件。
     */
    public long countFilesByProject(Long projectId) {
        List<Long> blockIds = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                        .eq(Block::getProjectId, projectId))
                .stream().map(Block::getId).toList();
        if (blockIds.isEmpty()) {
            return 0;
        }
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, blockIds));
        return attachments.stream()
                .filter(a -> STORED_NAME_PATTERN.matcher(a.getStoredName()).matches())
                .count();
    }

    /** 附件物理清理结果：删除的附件记录数 + 磁盘文件数 */
    public record AttachmentCleanupResult(int records, int files) {
    }

    /**
     * 准备鉴权下载（需查看权限）：校验附件归属后返回文件路径与元信息。
     */
    public DownloadPayload prepareDownload(Long userId, Long taskId, Long attachmentId) {
        Attachment attachment = requireAttachment(taskId, attachmentId);
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.VIEW_PROJECT);
        Path file = resolveFilePath(attachment);
        if (!Files.exists(file)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "attachment.notFound");
        }
        return new DownloadPayload(file, attachment);
    }

    /** 下载载荷：文件路径 + 附件元信息 */
    public record DownloadPayload(Path file, Attachment attachment) {
    }

    /** 校验附件存在且属于指定任务 */
    private Attachment requireAttachment(Long taskId, Long attachmentId) {
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !attachment.getTaskId().equals(taskId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "attachment.notFound");
        }
        return attachment;
    }

    /** 定位附件磁盘文件：优先私有目录，兼容迁移前遗留在公开目录的文件 */
    private Path resolveFilePath(Attachment attachment) {
        Path privateFile = attachmentDirPath().resolve(attachment.getStoredName());
        if (Files.exists(privateFile)) {
            return privateFile;
        }
        return uploadDirPath().resolve(attachment.getStoredName());
    }

    private Path uploadDirPath() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    private Path attachmentDirPath() {
        return Paths.get(attachmentDir).toAbsolutePath().normalize();
    }

    /**
     * 从任务描述中移除引用指定附件的 Markdown 图片语法（![alt](/api/v1/upload/files/xxx.ext)）。
     */
    private void removeDescriptionReference(Block card, String storedName, Long userId) {
        Map<String, Object> fields = card.getFields();
        if (fields == null) {
            return;
        }
        Object description = fields.get("description");
        if (!(description instanceof String) || ((String) description).isEmpty()) {
            return;
        }
        String url = "/api/v1/upload/files/" + storedName;
        String updated = ((String) description).replaceAll(
                "!\\[[^\\]]*\\]\\(\\Q" + url + "\\E\\)", "");
        if (updated.equals(description)) {
            return;
        }
        Map<String, Object> newFields = new HashMap<>(fields);
        newFields.put("description", updated);
        card.setFields(newFields);
        card.setModifiedBy(userId);
        blockMapper.updateById(card);
    }

    /**
     * 查询未删除的 card 块（任务）。
     */
    private Block getCard(Long taskId) {
        Block card = blockMapper.selectById(taskId);
        if (card == null || card.getDeleteAt() != null || !BlockTypes.CARD.equals(card.getType())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "task.notFound");
        }
        return card;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private AttachmentVO toVO(Attachment attachment) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(attachment.getId());
        vo.setTaskId(attachment.getTaskId());
        vo.setOriginalName(attachment.getOriginalName());
        // 图片保持公开静态地址（Markdown 引用与缩略图需要）；非图片走鉴权下载接口
        boolean image = IMAGE_EXTENSIONS.contains(extensionOf(attachment.getStoredName()));
        vo.setUrl(image
                ? "/api/v1/upload/files/" + attachment.getStoredName()
                : "/api/v1/tasks/" + attachment.getTaskId() + "/attachments/" + attachment.getId() + "/download");
        vo.setContentType(attachment.getContentType());
        vo.setSize(attachment.getSize());
        vo.setCreatedBy(attachment.getCreatedBy());
        vo.setCreatedAt(attachment.getCreateAt() != null
                ? attachment.getCreateAt().format(TIME_FORMATTER) : null);
        User creator = userMapper.selectById(attachment.getCreatedBy());
        if (creator != null) {
            vo.setCreatedByName(creator.getNickname());
        }
        return vo;
    }
}
