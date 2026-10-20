package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.dto.AttachmentVO;
import com.protask.entity.Attachment;
import com.protask.entity.Block;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.storage.LocalFileStorage;
import com.protask.storage.StorageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AttachmentService 单元测试：附件分目录存储、数量配额与项目删除级联清理。
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
        StorageRegistry storageRegistry = new StorageRegistry(
                new LocalFileStorage(tempDir.resolve("uploads")),
                new LocalFileStorage(tempDir.resolve("uploads-attachments")));
        attachmentService = new AttachmentService(attachmentMapper, blockMapper, userMapper,
                permissionService, storageRegistry);
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
    void deleteByProjectRemovesNestedFileAndRecord() throws IOException {
        String storedName = PROJECT_ID + "/" + CARD_ID + "/" + "b".repeat(32) + ".png";
        Path file = tempDir.resolve("uploads").resolve(storedName);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});

        when(blockMapper.selectList(any())).thenReturn(List.of(cardBlock()));
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment(storedName)));

        attachmentService.deleteByProject(PROJECT_ID);

        assertFalse(Files.exists(file), "分目录存储的文件应被物理删除");
        verify(attachmentMapper).deleteById(1L);
    }

    @Test
    void deleteByProjectSkipsNestedTraversalName() {
        when(blockMapper.selectList(any())).thenReturn(List.of(cardBlock()));
        when(attachmentMapper.selectList(any()))
                .thenReturn(List.of(attachment(PROJECT_ID + "/" + CARD_ID + "/../../evil.png")));

        attachmentService.deleteByProject(PROJECT_ID);

        verify(attachmentMapper, never()).deleteById(anyLong());
    }

    @Test
    void uploadStoresFileUnderProjectTaskDirectory() throws IOException {
        when(blockMapper.selectById(CARD_ID)).thenReturn(cardBlock());
        when(attachmentMapper.insert(any(Attachment.class))).thenAnswer(invocation -> {
            Attachment saved = invocation.getArgument(0);
            saved.setId(9L);
            return 1;
        });
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1, 2, 3});

        AttachmentVO vo = attachmentService.upload(1L, CARD_ID, file);

        Path taskDir = tempDir.resolve("uploads-attachments").resolve(PROJECT_ID.toString()).resolve(CARD_ID.toString());
        assertTrue(Files.exists(taskDir), "附件应落在 {projectId}/{taskId} 分目录下");
        try (var files = Files.list(taskDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".pdf")));
        }
        assertEquals("/api/v1/tasks/" + CARD_ID + "/attachments/9/download", vo.getUrl());
    }

    @Test
    void uploadRejectsWhenTaskQuotaReached() {
        ReflectionTestUtils.setField(attachmentService, "maxPerTask", 1);
        when(blockMapper.selectById(CARD_ID)).thenReturn(cardBlock());
        when(attachmentMapper.selectCount(any())).thenReturn(1L);
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1});

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attachmentService.upload(1L, CARD_ID, file));

        assertEquals("attachment.taskQuotaExceeded", ex.getMessageKey());
    }

    @Test
    void deleteByProjectWithoutCardsDoesNothing() {
        when(blockMapper.selectList(any())).thenReturn(List.of());

        attachmentService.deleteByProject(PROJECT_ID);

        verify(attachmentMapper, never()).selectList(any());
    }
}
