package com.protask.config;

import com.protask.storage.LocalFileStorage;
import com.protask.storage.StorageRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储配置：公开图片与私有附件两个本地存储桶。
 * 接入对象存储时替换此处实现即可，业务层只依赖 StorageRegistry。
 */
@Configuration
public class FileStorageConfig {

    @Bean
    public StorageRegistry storageRegistry(
            @Value("${protask.upload-dir:uploads}") String uploadDir,
            @Value("${protask.attachment-dir:uploads-attachments}") String attachmentDir) {
        return new StorageRegistry(new LocalFileStorage(uploadDir), new LocalFileStorage(attachmentDir));
    }
}
