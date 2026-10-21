package com.protask.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 存储配置：公开图片桶与私有附件桶分离。
 * 凭证仅通过环境变量注入（PROTASK_OSS_ACCESS_KEY_ID / SECRET），禁止写入仓库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "protask.storage.oss")
public class OssStorageProperties {

    /** OSS 服务接入点，如 oss-cn-hangzhou.aliyuncs.com（同地域 ECS 建议用 internal 内网域名免流量费） */
    private String endpoint = "";

    /** RAM 子账号 AccessKey Id（仅授予下列两个 bucket 的必要权限） */
    private String accessKeyId = "";

    /** RAM 子账号 AccessKey Secret */
    private String accessKeySecret = "";

    /** 公开图片桶名（可为公共读，但本项目默认仍走后端同源代理，桶保持私有亦可） */
    private String publicBucket = "";

    /** 私有附件桶名（必须为私有读写，仅后端凭证可访问） */
    private String privateBucket = "";

    /**
     * 公开图片对象 key 前缀：DB 中 upload_image.stored_name 存的是桶内相对 key，
     * 前缀仅用于在同一 bucket 内划分子空间，不参与业务 key 计算。
     */
    private String publicPrefix = "uploads/";

    /** 私有附件对象 key 前缀（同 publicPrefix 口径） */
    private String privatePrefix = "attachments/";

    /** 单次请求超时（毫秒） */
    private int socketTimeoutMillis = 10_000;

    /** 连接超时（毫秒） */
    private int connectionTimeoutMillis = 5_000;

    /** 客户端最大连接数 */
    private int maxConnections = 64;
}
