package com.protask.controller;

import com.protask.storage.FileStorage;
import com.protask.storage.StorageRegistry;
import com.protask.storage.oss.OssFileStorage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * 公开图片读取接口（仅 backend=oss 时启用，local 模式由 SaTokenConfig 的静态资源映射承担）。
 *
 * <p>URL 契约 {@code /api/v1/upload/files/{key}} 必须与本地模式等价：该路径已写入任务描述、
 * 项目描述与站点 logo 等库内 Markdown 文本，且图片附件按 {@code {projectId}/{taskId}/{uuid}.ext}
 * 分目录存放（Markdown 插图为扁平 {@code {uuid}.ext}），因此切换存储后端不应产生数据迁移。
 *
 * <p>安全边界：key 严格匹配「可选两级数字目录 + 32 位十六进制 UUID + 图片扩展名」，
 * 故这个免登录接口只能取到公开图片桶内的图片对象，
 * 既读不到私有附件桶，也读不到公开桶里万一存在的其它类型文件。
 */
@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "protask.storage.backend", havingValue = "oss")
public class PublicImageController {

    /** 图片访问路径前缀，与 SaTokenConfig 的静态映射及本地模式 URL 口径一致 */
    private static final String PATH_PREFIX = "/api/v1/upload/files/";

    /** 本站生成的图片 key：Markdown 插图扁平存放，任务图片附件按 项目/任务 两级目录存放 */
    private static final Pattern IMAGE_KEY_PATTERN =
            Pattern.compile("(?:\\d{1,19}/\\d{1,19}/)?[0-9a-f]{32}\\.(?:png|jpg|jpeg|gif|webp|bmp)");

    /** 强缓存一年：key 含 UUID，对象内容不可变 */
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final StorageRegistry storageRegistry;

    @GetMapping("/files/**")
    public void getImage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String key = extractKey(request);
        if (key == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String eTag = "\"" + key + "\"";
        if (eTag.equals(request.getHeader("If-None-Match"))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        FileStorage storage = storageRegistry.publicImages();
        long size = storage.size(key);
        if (size < 0) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(OssFileStorage.contentTypeOf(key));
        response.setHeader("Cache-Control", CACHE_CONTROL);
        response.setHeader("ETag", eTag);
        response.setContentLengthLong(size);
        // 图片不涉及断点续传，不支持 Range；Markdown 中的 <img> 一律整图加载
        try (InputStream content = storage.open(key)) {
            content.transferTo(response.getOutputStream());
        }
    }

    /**
     * 取图片前缀之后的相对 key 并做白名单校验：不合规（路径穿越、绝对路径、
     * 非图片扩展名、多级目录）一律返回 null，由调用方转 404 且不泄露对象是否存在。
     */
    private String extractKey(HttpServletRequest request) {
        String prefix = (request.getContextPath() == null ? "" : request.getContextPath()) + PATH_PREFIX;
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(prefix)) {
            return null;
        }
        String key = uri.substring(prefix.length());
        return IMAGE_KEY_PATTERN.matcher(key).matches() ? key : null;
    }
}
