package com.protask.service;

import com.protask.common.BusinessException;
import com.protask.config.StorageProperties;
import com.protask.dto.StorageCleanupVO;
import com.protask.dto.StorageStatsVO;
import com.protask.entity.Block;
import com.protask.entity.UploadImage;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.SiteSettingMapper;
import com.protask.mapper.UploadImageMapper;
import com.protask.permission.PermissionService;
import com.protask.storage.LocalFileStorage;
import com.protask.storage.StorageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * StorageAdminService 单元测试：孤儿图片判定/清理与用量统计。
 */
@ExtendWith(MockitoExtension.class)
class StorageAdminServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final String ORPHAN = "a".repeat(32) + ".png";
    private static final String REFERENCED = "b".repeat(32) + ".png";
    private static final String RECENT = "c".repeat(32) + ".png";

    @TempDir
    Path tempDir;

    @Mock
    private UploadImageMapper uploadImageMapper;

    @Mock
    private AttachmentMapper attachmentMapper;

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private SiteSettingMapper siteSettingMapper;

    @Mock
    private PermissionService permissionService;

    private LocalFileStorage publicStorage;
    private StorageProperties properties;
    private StorageAdminService storageAdminService;

    @BeforeEach
    void setUp() {
        publicStorage = new LocalFileStorage(tempDir.resolve("uploads"));
        StorageRegistry registry = new StorageRegistry(
                publicStorage, new LocalFileStorage(tempDir.resolve("uploads-attachments")));
        properties = new StorageProperties();
        properties.setOrphanRetentionDays(7);
        // 生产默认关闭，本类的判定/清理用例需显式打开开关
        properties.setOrphanCheckEnabled(true);
        storageAdminService = new StorageAdminService(registry, properties, permissionService,
                uploadImageMapper, attachmentMapper, blockMapper, projectMapper, siteSettingMapper);
    }

    private UploadImage image(long id, String storedName, int daysAgo) {
        UploadImage image = new UploadImage();
        image.setId(id);
        image.setStoredName(storedName);
        image.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        return image;
    }

    private void store(String name, String content) throws IOException {
        publicStorage.store(name, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private void stubCandidateImages() throws IOException {
        store(ORPHAN, "orphan");
        store(REFERENCED, "kept");
        store(RECENT, "recent");
        when(uploadImageMapper.selectList(any())).thenReturn(List.of(
                image(1L, ORPHAN, 30),
                image(2L, REFERENCED, 30),
                image(3L, RECENT, 1)));
    }

    private void stubReferences() {
        Block block = new Block();
        Map<String, Object> fields = new HashMap<>();
        fields.put("description", "![图片](/api/v1/upload/files/" + REFERENCED + ")");
        block.setFields(fields);
        when(blockMapper.selectList(any())).thenReturn(List.of(block));
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(siteSettingMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void cleanupRemovesOnlyUnreferencedExpiredImages() throws IOException {
        when(permissionService.isSystemAdmin(ADMIN_ID)).thenReturn(true);
        stubCandidateImages();
        stubReferences();

        StorageCleanupVO result = storageAdminService.cleanupOrphans(ADMIN_ID);

        assertEquals(1, result.getDeletedImages());
        assertEquals(1, result.getDeletedFiles());
        assertEquals("orphan".length(), result.getFreedBytes());
        assertFalse(publicStorage.exists(ORPHAN), "无引用且超期的图片应被删除");
        assertTrue(publicStorage.exists(REFERENCED), "描述中引用的图片必须保留");
        assertTrue(publicStorage.exists(RECENT), "保留期内的图片必须保留");
        verify(uploadImageMapper).deleteById(1L);
    }

    @Test
    void statsReportsOrphansAndBuckets() throws IOException {
        when(permissionService.isSystemAdmin(ADMIN_ID)).thenReturn(true);
        when(attachmentMapper.selectCount(any())).thenReturn(3L);
        when(uploadImageMapper.selectCount(any())).thenReturn(3L);
        stubCandidateImages();
        stubReferences();

        StorageStatsVO stats = storageAdminService.stats(ADMIN_ID);

        assertTrue(stats.isOrphanCheckEnabled());
        assertEquals(1, stats.getOrphanImages());
        assertEquals(3, stats.getUploadImageRecords());
        assertEquals(3, stats.getAttachmentRecords());
        assertEquals(3, stats.getPublicImages().getFileCount());
        assertEquals(7, stats.getOrphanRetentionDays());
        assertFalse(stats.isWarning());
    }

    @Test
    void orphanCheckDisabledSkipsScanAndRejectsCleanup() throws IOException {
        properties.setOrphanCheckEnabled(false);
        when(permissionService.isSystemAdmin(ADMIN_ID)).thenReturn(true);
        when(attachmentMapper.selectCount(any())).thenReturn(0L);
        when(uploadImageMapper.selectCount(any())).thenReturn(3L);

        StorageStatsVO stats = storageAdminService.stats(ADMIN_ID);

        assertFalse(stats.isOrphanCheckEnabled(), "开关状态需下发给前端，用于隐藏清理入口");
        assertEquals(0, stats.getOrphanImages(), "关闭后不做孤儿判定");
        assertEquals(0, stats.getOrphanImageBytes());
        assertEquals(3, stats.getUploadImageRecords(), "记录数走 COUNT，不加载全表");
        // 关闭后不应再全表扫描引用文本，也不应逐张查对象大小
        verifyNoInteractions(blockMapper, projectMapper, siteSettingMapper);
        verify(uploadImageMapper, never()).selectList(any());
        assertThrows(BusinessException.class, () -> storageAdminService.cleanupOrphans(ADMIN_ID));
    }

    @Test
    void rejectsNonAdmin() {
        when(permissionService.isSystemAdmin(ADMIN_ID)).thenReturn(false);

        assertThrows(BusinessException.class, () -> storageAdminService.stats(ADMIN_ID));
        assertThrows(BusinessException.class, () -> storageAdminService.cleanupOrphans(ADMIN_ID));
    }
}
