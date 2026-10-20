package com.protask.service;

import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.config.StorageProperties;
import com.protask.dto.StorageCleanupVO;
import com.protask.dto.StorageStatsVO;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.entity.SiteSetting;
import com.protask.entity.UploadImage;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.SiteSettingMapper;
import com.protask.mapper.UploadImageMapper;
import com.protask.permission.PermissionService;
import com.protask.storage.FileStorage;
import com.protask.storage.StorageRegistry;
import com.protask.storage.StorageStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存储运维服务：用量统计与孤儿图片清理（仅系统管理员）。
 * 孤立判定口径：upload_image 记录未被任何块字段/标题、项目描述、站点设置引用，
 * 且上传时间早于保留期；清理前逐个删除文件，文件删除失败则保留记录供下次重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageAdminService {

    /** 本站上传图片文件名：32 位十六进制 UUID + 图片扩展名 */
    private static final Pattern IMAGE_NAME_PATTERN = Pattern.compile(
            "[0-9a-f]{32}\\.(?:png|jpg|jpeg|gif|webp|bmp)");

    private final StorageRegistry storageRegistry;
    private final StorageProperties storageProperties;
    private final PermissionService permissionService;
    private final UploadImageMapper uploadImageMapper;
    private final AttachmentMapper attachmentMapper;
    private final BlockMapper blockMapper;
    private final ProjectMapper projectMapper;
    private final SiteSettingMapper siteSettingMapper;

    /** 存储用量 + 孤儿图片统计（管理后台展示，仅系统管理员） */
    public StorageStatsVO stats(Long operatorId) {
        requireAdmin(operatorId);
        return buildStats();
    }

    /** 清理无引用且超过保留期的孤儿图片（仅系统管理员） */
    public StorageCleanupVO cleanupOrphans(Long operatorId) {
        requireAdmin(operatorId);
        Set<String> referenced = collectReferencedImageNames();
        LocalDateTime cutoff = orphanCutoff();
        StorageCleanupVO result = new StorageCleanupVO();
        FileStorage publicStorage = storageRegistry.publicImages();
        for (UploadImage image : uploadImageMapper.selectList(null)) {
            if (!isOrphan(image, referenced, cutoff)) {
                continue;
            }
            String name = image.getStoredName();
            boolean fileExists = publicStorage.exists(name);
            long bytes = fileExists ? Math.max(publicStorage.size(name), 0) : 0;
            try {
                if (fileExists) {
                    publicStorage.delete(name);
                    result.setDeletedFiles(result.getDeletedFiles() + 1);
                    result.setFreedBytes(result.getFreedBytes() + bytes);
                }
            } catch (IOException e) {
                // 文件删除失败时保留记录，避免元数据先行丢失导致文件永久失联
                log.warn("孤儿图片清理：删除文件失败 name={}", name, e);
                continue;
            }
            uploadImageMapper.deleteById(image.getId());
            result.setDeletedImages(result.getDeletedImages() + 1);
        }
        log.info("孤儿图片清理完成：记录 {} 条，文件 {} 个，释放 {} 字节",
                result.getDeletedImages(), result.getDeletedFiles(), result.getFreedBytes());
        return result;
    }

    /** 构建用量快照（含孤儿图片预览与阈值告警标记） */
    public StorageStatsVO buildStats() {
        StorageStatsVO vo = new StorageStatsVO();
        vo.setPublicImages(buildBucket(storageRegistry.publicImages()));
        vo.setPrivateAttachments(buildBucket(storageRegistry.privateAttachments()));
        vo.setAttachmentRecords(attachmentMapper.selectCount(null));

        List<UploadImage> images = uploadImageMapper.selectList(null);
        vo.setUploadImageRecords(images.size());
        Set<String> referenced = collectReferencedImageNames();
        LocalDateTime cutoff = orphanCutoff();
        long orphanCount = 0;
        long orphanBytes = 0;
        for (UploadImage image : images) {
            if (!isOrphan(image, referenced, cutoff)) {
                continue;
            }
            orphanCount++;
            long size = storageRegistry.publicImages().size(image.getStoredName());
            if (size > 0) {
                orphanBytes += size;
            }
        }
        vo.setOrphanImages(orphanCount);
        vo.setOrphanImageBytes(orphanBytes);
        vo.setOrphanRetentionDays(storageProperties.getOrphanRetentionDays());
        vo.setWarnFileCount(storageProperties.getWarnFileCount());
        vo.setWarnTotalBytes(storageProperties.getWarnTotalBytes());

        long totalFiles = vo.getPublicImages().getFileCount() + vo.getPrivateAttachments().getFileCount();
        long totalBytes = vo.getPublicImages().getTotalBytes() + vo.getPrivateAttachments().getTotalBytes();
        vo.setWarning(totalFiles >= storageProperties.getWarnFileCount()
                || totalBytes >= storageProperties.getWarnTotalBytes());
        return vo;
    }

    private StorageStatsVO.Bucket buildBucket(FileStorage storage) {
        try {
            StorageStats stats = storage.stats();
            StorageStatsVO.Bucket bucket = new StorageStatsVO.Bucket();
            bucket.setFileCount(stats.fileCount());
            bucket.setTotalBytes(stats.totalBytes());
            return bucket;
        } catch (IOException e) {
            log.warn("存储用量统计失败", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "storage.statsFailed");
        }
    }

    /** 孤儿判定：无引用 + 上传时间早于保留期（创建时间缺失按孤儿处理） */
    private boolean isOrphan(UploadImage image, Set<String> referenced, LocalDateTime cutoff) {
        String name = image.getStoredName();
        if (name == null || referenced.contains(name)) {
            return false;
        }
        return image.getCreatedAt() == null || image.getCreatedAt().isBefore(cutoff);
    }

    private LocalDateTime orphanCutoff() {
        return LocalDateTime.now().minusDays(Math.max(storageProperties.getOrphanRetentionDays(), 0));
    }

    private void requireAdmin(Long operatorId) {
        if (!permissionService.isSystemAdmin(operatorId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin.permissionDenied");
        }
    }

    /**
     * 汇总仍被引用的图片文件名：块字段/标题（任务描述、评论等）、项目名称/描述、站点设置。
     * 用文件名正则从文本中提取，避免逐张图片全表扫描。
     */
    private Set<String> collectReferencedImageNames() {
        Set<String> names = new HashSet<>();
        for (SiteSetting setting : siteSettingMapper.selectList(null)) {
            collectNames(setting.getSettingValue(), names);
        }
        for (Project project : projectMapper.selectList(null)) {
            collectNames(project.getName(), names);
            collectNames(project.getDescription(), names);
        }
        for (Block block : blockMapper.selectList(null)) {
            collectNames(block.getTitle(), names);
            if (block.getFields() != null) {
                collectNames(block.getFields().toString(), names);
            }
        }
        return names;
    }

    private void collectNames(String text, Set<String> names) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = IMAGE_NAME_PATTERN.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group());
        }
    }
}
