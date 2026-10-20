package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.dto.BlockPatchRequest;
import com.protask.entity.Block;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BlockService 单元测试：PATCH 字段合并与软删除。
 */
@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long BLOCK_ID = 20L;
    private static final Long PROJECT_ID = 100L;

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PermissionService permissionService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private NotificationService notificationService;

    private BlockService blockService;

    @BeforeEach
    void setUp() {
        blockService = new BlockService(blockMapper, userMapper, permissionService, attachmentService, notificationService);
        User user = new User();
        user.setId(USER_ID);
        user.setNickname("tester");
        lenient().when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user));
    }

    private Block textBlock() {
        Block block = new Block();
        block.setId(BLOCK_ID);
        block.setProjectId(PROJECT_ID);
        block.setType(BlockTypes.TEXT);
        block.setTitle("块");
        block.setCreatedBy(USER_ID);
        block.setModifiedBy(USER_ID);
        Map<String, Object> fields = new HashMap<>();
        fields.put("content", "old");
        fields.put("temp", "to-remove");
        block.setFields(fields);
        return block;
    }

    @Test
    void patchMergesUpdatedFieldsAndRemovesDeletedFields() {
        Block block = textBlock();
        when(blockMapper.selectById(BLOCK_ID)).thenReturn(block);

        BlockPatchRequest request = new BlockPatchRequest();
        request.setUpdatedFields(Map.of("content", "new"));
        request.setDeletedFields(List.of("temp"));
        blockService.patchBlock(USER_ID, BLOCK_ID, request);

        assertEquals("new", block.getFields().get("content"));
        assertFalse(block.getFields().containsKey("temp"));
        assertEquals(USER_ID, block.getModifiedBy());
    }

    @Test
    void deleteMarksDeleteAt() {
        Block block = textBlock();
        when(blockMapper.selectById(BLOCK_ID)).thenReturn(block);
        blockService.deleteBlock(USER_ID, BLOCK_ID);
        assertNotNull(block.getDeleteAt());
    }

    @Test
    void patchDeletedBlockThrowsNotFound() {
        Block block = textBlock();
        block.setDeleteAt(LocalDateTime.now());
        when(blockMapper.selectById(BLOCK_ID)).thenReturn(block);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> blockService.patchBlock(USER_ID, BLOCK_ID, new BlockPatchRequest()));
        assertEquals("block.notFound", ex.getMessageKey());
    }

    @Test
    void patchOwnCommentRequiresCommentPermission() {
        Block comment = textBlock();
        comment.setType(BlockTypes.COMMENT);
        comment.setCreatedBy(USER_ID);
        when(blockMapper.selectById(BLOCK_ID)).thenReturn(comment);

        blockService.patchBlock(USER_ID, BLOCK_ID, new BlockPatchRequest());

        verify(permissionService).checkPermission(USER_ID, PROJECT_ID, ProjectPermission.COMMENT_PROJECT_CARDS);
    }

    @Test
    void patchOthersCommentRequiresDeleteOthersCommentsPermission() {
        Block comment = textBlock();
        comment.setType(BlockTypes.COMMENT);
        comment.setCreatedBy(99L);
        when(blockMapper.selectById(BLOCK_ID)).thenReturn(comment);

        blockService.patchBlock(USER_ID, BLOCK_ID, new BlockPatchRequest());

        verify(permissionService).checkPermission(USER_ID, PROJECT_ID, ProjectPermission.DELETE_OTHERS_COMMENTS);
    }

    @Test
    void softDeleteByProjectCascadesAttachmentsAndMarksBlocks() {
        Block card = textBlock();
        card.setType(BlockTypes.CARD);
        when(blockMapper.selectList(any())).thenReturn(List.of(card));

        blockService.softDeleteByProject(PROJECT_ID, USER_ID);

        verify(attachmentService).deleteByProject(PROJECT_ID);
        assertNotNull(card.getDeleteAt());
    }
}
