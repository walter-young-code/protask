package com.protask.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.protask.storage.LocalFileStorage;
import com.protask.storage.StorageRegistry;
import com.protask.storage.oss.OssFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 存储装配：按 {@code protask.storage.backend} 在本地磁盘与阿里云 OSS 之间切换。
 * 业务层只依赖 {@link StorageRegistry}，两种后端对上层完全等价。
 */
@Slf4j
@Configuration
public class FileStorageConfig {

    /** 本地磁盘模式（默认，适合开发与未接对象存储的环境） */
    @Bean
    @ConditionalOnProperty(name = "protask.storage.backend", havingValue = "local", matchIfMissing = true)
    public StorageRegistry localStorageRegistry(
            @Value("${protask.upload-dir:uploads}") String uploadDir,
            @Value("${protask.attachment-dir:uploads-attachments}") String attachmentDir) {
        log.info("存储后端：本地磁盘（公开图片={}，私有附件={})", uploadDir, attachmentDir);
        return new StorageRegistry(new LocalFileStorage(uploadDir), new LocalFileStorage(attachmentDir));
    }

    /**
     * OSS 客户端：仅 backend=oss 时创建，随容器关闭释放连接池。
     * 必填项缺失直接启动失败，避免运行到上传时才暴露配置错误。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "protask.storage.backend", havingValue = "oss")
    public OSS ossClient(OssStorageProperties properties) {
        requireText(properties.getEndpoint(), "PROTASK_OSS_ENDPOINT");
        requireText(properties.getAccessKeyId(), "PROTASK_OSS_ACCESS_KEY_ID");
        requireText(properties.getAccessKeySecret(), "PROTASK_OSS_ACCESS_KEY_SECRET");
        requireText(properties.getPublicBucket(), "PROTASK_OSS_PUBLIC_BUCKET");
        requireText(properties.getPrivateBucket(), "PROTASK_OSS_PRIVATE_BUCKET");
        if (properties.getPublicBucket().equals(properties.getPrivateBucket())
                && properties.getPublicPrefix().equals(properties.getPrivatePrefix())) {
            // 同桶同前缀会让公开图片与私有附件落在同一空间，匿名图片接口可顺带读到附件
            throw new IllegalStateException(
                    "公开桶与私有桶相同时必须配置不同的 key 前缀（PROTASK_OSS_PUBLIC_PREFIX / PROTASK_OSS_PRIVATE_PREFIX）");
        }

        ClientBuilderConfiguration clientConfig = new ClientBuilderConfiguration();
        clientConfig.setMaxConnections(properties.getMaxConnections());
        clientConfig.setSocketTimeout(properties.getSocketTimeoutMillis());
        clientConfig.setConnectionTimeout(properties.getConnectionTimeoutMillis());
        log.info("存储后端：阿里云 OSS（endpoint={}，公开桶={}，私有桶={}）",
                properties.getEndpoint(), properties.getPublicBucket(), properties.getPrivateBucket());
        return new OSSClientBuilder().build(properties.getEndpoint(), properties.getAccessKeyId(),
                properties.getAccessKeySecret(), clientConfig);
    }

    /** 阿里云 OSS 模式：公开图片与私有附件两个桶 */
    @Bean
    @ConditionalOnProperty(name = "protask.storage.backend", havingValue = "oss")
    public StorageRegistry ossStorageRegistry(OSS ossClient, OssStorageProperties properties) {
        return new StorageRegistry(
                new OssFileStorage(ossClient, properties.getPublicBucket(), properties.getPublicPrefix()),
                new OssFileStorage(ossClient, properties.getPrivateBucket(), properties.getPrivatePrefix()));
    }

    private void requireText(String value, String envName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("protask.storage.backend=oss 时该配置必填，请通过环境变量 "
                    + envName + " 注入");
        }
    }
}
