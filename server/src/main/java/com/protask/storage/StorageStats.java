package com.protask.storage;

/**
 * 存储桶用量统计。
 *
 * @param fileCount  文件数
 * @param totalBytes 总字节数
 */
public record StorageStats(long fileCount, long totalBytes) {

    public static final StorageStats EMPTY = new StorageStats(0, 0);
}
