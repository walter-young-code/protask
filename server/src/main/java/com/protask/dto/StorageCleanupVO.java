package com.protask.dto;

import lombok.Data;

/**
 * 孤儿图片清理结果。
 */
@Data
public class StorageCleanupVO {

    /** 删除的 upload_image 记录数 */
    private long deletedImages;

    /** 实际删除的磁盘文件数 */
    private long deletedFiles;

    /** 释放的字节数 */
    private long freedBytes;
}
