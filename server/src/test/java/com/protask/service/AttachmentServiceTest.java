package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.entity.Attachment;
import com.protask.entity.Block;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AttachmentService 单元测试：项目删除级联清理附件（deleteByProject）。
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    private static final Long PROJECT_ID = 100L;
    private static final Long CARD_ID = 20L;

    @TempDir
    Path tempDir;

    @Mock
    private AttachmentMapper attachmentMapper;

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PermissionService permissionService;

    private AttachmentService attachmentService;

    @BeforeEach
    void setUp() {
        attachmentService = new AttachmentService(attachmentMapper, blockMapper, userMapper, permissionService);
        ReflectionTestUtils.setField(attachmentService, "uploadDir", tempDir.resolve("uploads").toString());
        ReflectionTestUtils.setField(attachmentService, "attachmentDir", tempDir.resolve("uploads-attachments").toString());
    }

    private Block cardBlock() {
        Block card = new Block();
        card.setId(CARD_ID);
        card.setProjectId(PROJECT_ID);
        card.setType(BlockTypes.CARD);
        return card;
    }

    private Attachment attachment(String storedName) {
        Attachment attachment = new Attachment();
        attachment.setId(1L);
        attachment.setTaskId(CARD_ID);
        attachment.setStoredName(storedName);
        return attachment;
    }

    @Test
    void deleteByProjectRemovesFileAndRecord() throws IOException {
        String storedName = "a".repeat(32) + ".png";
        Path file = tempDir.resolve("uploads").resolve(storedName);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});

        when(blockMapper.selectList(any())).thenReturn(List.of(cardBlock()));
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment(storedName)));

        attachmentService.deleteByProject(PROJECT_ID);

        assertFalse(Files.exists(file), "磁盘文件应被物理删除");
        verify(attachmentMapper).deleteById(1L);
    }

    @Test
    void deleteByProjectSkipsInvalidStoredName() {
        when(blockMapper.selectList(any())).thenReturn(List.of(cardBlock()));
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment("../../evil.png")));

        attachmentService.deleteByProject(PROJECT_ID);

        verify(attachmentMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteByProjectWithoutCardsDoesNothing() {
        when(blockMapper.selectList(any())).thenReturn(List.of());

        attachmentService.deleteByProject(PROJECT_ID);

        verify(attachmentMapper, never()).selectList(any());
    }
}
