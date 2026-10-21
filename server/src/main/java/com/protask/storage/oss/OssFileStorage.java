package com.protask.storage.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import com.protask.storage.FileStorage;
import com.protask.storage.StorageStats;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 阿里云 OSS 存储实现：业务 key（如 {@code 3/17/{uuid32}.pdf}）映射为
 * {@code keyPrefix + key} 的对象名，与 {@link com.protask.storage.LocalFileStorage}
 * 的相对路径口径一致，因此切换后端无需改动 DB 中已存的 stored_name。
 *
 * <p>接口的六个方法都能用 OSS 单次 API 表达，业务层（上传/下载/孤儿清理）零改动：
 * 孤儿清理以 upload_image 记录为准逐个 exists/size/delete，不依赖目录列举。
 */
@Slf4j
public class OssFileStorage implements FileStorage {

    /** OSS 对象不存在时的错误码 */
    private static final String NO_SUCH_KEY = "NoSuchKey";

    /** ListObjectsV2 单页最大对象数（OSS 上限 1000） */
    private static final int LIST_PAGE_SIZE = 1000;

    private final OSS ossClient;
    private final String bucketName;
    private final String keyPrefix;

    public OssFileStorage(OSS ossClient, String bucketName, String keyPrefix) {
        this.ossClient = ossClient;
        this.bucketName = bucketName;
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public void store(String key, InputStream content) throws IOException {
        String objectKey = resolve(key);
        // SDK 需要显式 ContentLength 才能可靠判断请求体边界；整读入内存的上限由
        // spring.servlet.multipart.max-file-size（默认 20MB）约束，不会造成无界占用
        byte[] bytes = content.readAllBytes();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentTypeOf(key));
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            ossClient.putObject(bucketName, objectKey, in, metadata);
        } catch (RuntimeException e) {
            throw new IOException("写入 OSS 对象失败: " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return ossClient.doesObjectExist(bucketName, resolve(key));
        } catch (RuntimeException e) {
            log.warn("OSS 对象存在性检查失败 bucket={} key={}", bucketName, key, e);
            return false;
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        String objectKey = resolve(key);
        try {
            OSSObject object = ossClient.getObject(bucketName, objectKey);
            return object.getObjectContent();
        } catch (OSSException e) {
            if (NO_SUCH_KEY.equals(e.getErrorCode())) {
                throw new IOException("OSS 对象不存在: " + objectKey, e);
            }
            throw new IOException("读取 OSS 对象失败: " + objectKey, e);
        } catch (RuntimeException e) {
            throw new IOException("读取 OSS 对象失败: " + objectKey, e);
        }
    }

    @Override
    public long size(String key) {
        String objectKey;
        try {
            objectKey = resolve(key);
        } catch (IllegalArgumentException e) {
            log.warn("非法 object key: {}", key);
            return -1;
        }
        try {
            ObjectMetadata metadata = ossClient.getObjectMetadata(bucketName, objectKey);
            return metadata.getContentLength();
        } catch (OSSException e) {
            // 与 LocalFileStorage 口径一致：不存在返回 -1，不抛异常
            if (NO_SUCH_KEY.equals(e.getErrorCode())) {
                return -1;
            }
            log.warn("读取 OSS 对象大小失败 bucket={} key={}", bucketName, objectKey, e);
            return -1;
        } catch (RuntimeException e) {
            log.warn("读取 OSS 对象大小失败 bucket={} key={}", bucketName, objectKey, e);
            return -1;
        }
    }

    @Override
    public void delete(String key) throws IOException {
        String objectKey = resolve(key);
        try {
            // OSS deleteObject 对不存在的 key 同样返回 204，无需先判断存在性
            ossClient.deleteObject(bucketName, objectKey);
        } catch (RuntimeException e) {
            throw new IOException("删除 OSS 对象失败: " + objectKey, e);
        }
    }

    /**
     * 分页列举前缀下全部对象累计用量：仅存储巡检（每天 03:00）与管理后台展示调用，
     * 对象数达数万时会产生等比例的 ListObjectsV2 调用，属可接受的观测成本。
     */
    @Override
    public StorageStats stats() throws IOException {
        long fileCount = 0;
        long totalBytes = 0;
        try {
            String continuationToken = null;
            do {
                ListObjectsV2Request request = new ListObjectsV2Request(bucketName)
                        .withPrefix(keyPrefix)
                        .withMaxKeys(LIST_PAGE_SIZE);
                request.setContinuationToken(continuationToken);
                ListObjectsV2Result result = ossClient.listObjectsV2(request);
                for (OSSObjectSummary summary : result.getObjectSummaries()) {
                    // 前缀占位用的 0 字节目录对象不计入文件数
                    if (summary.getKey().endsWith("/")) {
                        continue;
                    }
                    fileCount++;
                    totalBytes += summary.getSize();
                }
                continuationToken = result.isTruncated() ? result.getNextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (RuntimeException e) {
            throw new IOException("统计 OSS 存储用量失败: bucket=" + bucketName + " prefix=" + keyPrefix, e);
        }
        return new StorageStats(fileCount, totalBytes);
    }

    /** 业务 key 转 OSS 对象名，拒绝空值、绝对路径与 ../ 形式的路径穿越 */
    private String resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("object key must not be blank");
        }
        String normalized = key.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.endsWith("/..")
                || normalized.contains("/./")) {
            throw new IllegalArgumentException("invalid object key: " + key);
        }
        return keyPrefix + normalized;
    }

    /** 归一化 key 前缀：空值表示根目录，非空时以单个 / 结尾 */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }

    /** 按扩展名推断 Content-Type：写入对象与后端同源代理图片时据此设置响应头 */
    public static String contentTypeOf(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "txt", "md" -> "text/plain;charset=UTF-8";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }
}
