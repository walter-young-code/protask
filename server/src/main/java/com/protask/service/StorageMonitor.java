package com.protask.service;

import com.protask.config.StorageProperties;
import com.protask.storage.StorageRegistry;
import com.protask.storage.StorageStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 存储巡检：定时统计两个存储桶的文件数与占用，超阈值输出 WARN。
 * 仅做观测告警，不自动删除任何文件；孤儿图片清理由管理后台手动触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageMonitor {

    private final StorageRegistry storageRegistry;
    private final StorageProperties storageProperties;

    @Scheduled(cron = "${protask.storage.check-cron:0 0 3 * * *}")
    public void check() {
        try {
            StorageStats publicStats = storageRegistry.publicImages().stats();
            StorageStats privateStats = storageRegistry.privateAttachments().stats();
            long files = publicStats.fileCount() + privateStats.fileCount();
            long bytes = publicStats.totalBytes() + privateStats.totalBytes();
            String summary = String.format(
                    "存储巡检：公开图片 %d 个/%.1fMB，私有附件 %d 个/%.1fMB，合计 %.1fMB",
                    publicStats.fileCount(), toMb(publicStats.totalBytes()),
                    privateStats.fileCount(), toMb(privateStats.totalBytes()), toMb(bytes));
            if (files >= storageProperties.getWarnFileCount()
                    || bytes >= storageProperties.getWarnTotalBytes()) {
                log.warn("{}，已超过告警阈值（{} 个 / {}MB）", summary,
                        storageProperties.getWarnFileCount(),
                        Math.round(toMb(storageProperties.getWarnTotalBytes())));
            } else {
                log.info("{}", summary);
            }
        } catch (IOException e) {
            log.warn("存储巡检失败", e);
        }
    }

    private double toMb(long bytes) {
        return bytes / 1024.0 / 1024.0;
    }
}
