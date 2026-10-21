package com.protask.dto;

import lombok.Data;

/**
 * 存储用量统计视图：公开图片/私有附件两个桶的用量 + 孤儿图片预览（管理后台展示）。
 */
@Data
public class StorageStatsVO {

    /** 公开图片桶（Markdown 插图、图片附件） */
    private Bucket publicImages;

    /** 私有附件桶（文档/压缩包等） */
    private Bucket privateAttachments;

    /** attachment 表记录数 */
    private long attachmentRecords;

    /** upload_image 表记录数 */
    private long uploadImageRecords;

    /** 无引用且超过保留期的孤儿图片数（未启用检测时恒为 0） */
    private long orphanImages;

    /** 孤儿图片占用字节数（未启用检测时恒为 0） */
    private long orphanImageBytes;

    /** 是否启用孤儿图片检测与清理（protask.storage.orphan-check-enabled） */
    private boolean orphanCheckEnabled;

    /** 孤儿图片保留天数 */
    private int orphanRetentionDays;

    /** 文件数告警阈值 */
    private long warnFileCount;

    /** 总容量告警阈值（字节） */
    private long warnTotalBytes;

    /** 是否已超过任一告警阈值 */
    private boolean warning;

    /** 单个存储桶的用量 */
    @Data
    public static class Bucket {
        private long fileCount;
        private long totalBytes;
    }
}
