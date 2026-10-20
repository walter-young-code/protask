package com.protask.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储运维配置：巡检阈值与孤儿图片保留策略。
 */
@Data
@Component
@ConfigurationProperties(prefix = "protask.storage")
public class StorageProperties {

    /** 文件数告警阈值（两个存储桶合计，超过时巡检日志输出 WARN） */
    private long warnFileCount = 50_000;

    /** 总占用告警阈值（字节，默认 5GB） */
    private long warnTotalBytes = 5L * 1024 * 1024 * 1024;

    /** 孤儿图片保留天数：上传未满该天数的图片即使暂无引用也不清理 */
    private int orphanRetentionDays = 7;
}
