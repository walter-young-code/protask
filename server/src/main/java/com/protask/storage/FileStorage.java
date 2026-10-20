package com.protask.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 对象存储抽象：以 / 分隔的相对 object key 存取文件，屏蔽本地磁盘与对象存储差异。
 * 当前实现为 {@link LocalFileStorage}；接入 MinIO/S3/OSS 时仅需新增实现并在
 * FileStorageConfig 中替换，业务层（上传/下载/清理）无需改动。
 */
public interface FileStorage {

    /** 写入对象（父目录自动创建，同名覆盖） */
    void store(String key, InputStream content) throws IOException;

    /** 对象是否存在 */
    boolean exists(String key);

    /** 读取对象（不存在时抛 IOException） */
    InputStream open(String key) throws IOException;

    /** 对象大小（字节），不存在返回 -1 */
    long size(String key);

    /** 删除对象（不存在时忽略） */
    void delete(String key) throws IOException;

    /** 统计对象数与总字节（供存储巡检与后台展示） */
    StorageStats stats() throws IOException;
}
