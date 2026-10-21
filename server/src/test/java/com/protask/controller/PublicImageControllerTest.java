package com.protask.controller;

import com.protask.storage.FileStorage;
import com.protask.storage.StorageRegistry;
import com.protask.storage.StorageStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OSS 模式下公开图片接口的行为测试：URL 契约（扁平插图 + 分目录图片附件）、
 * 响应缓存头，以及 key 白名单必须拒绝路径穿越与非图片对象。
 *
 * <p>用内存假实现代替 OSS 客户端，不依赖网络与 Mockito。
 */
class PublicImageControllerTest {

    private static final String UUID_NAME = "0123456789abcdef0123456789abcdef";
    private static final String FLAT_KEY = UUID_NAME + ".png";
    private static final String NESTED_KEY = "7/42/" + UUID_NAME + ".jpg";

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Set<String> accessedKeys = new HashSet<>();
    private PublicImageController controller;

    @BeforeEach
    void setUp() {
        objects.put(FLAT_KEY, "markdown-image".getBytes(StandardCharsets.UTF_8));
        objects.put(NESTED_KEY, "attachment-image".getBytes(StandardCharsets.UTF_8));
        controller = new PublicImageController(new StorageRegistry(new FakeStorage(), new FakeStorage()));
    }

    @Test
    @DisplayName("扁平 key 的 Markdown 插图可匿名读取，并带一年强缓存与 ETag")
    void servesFlatMarkdownImage() throws Exception {
        MockHttpServletResponse response = get("/api/v1/upload/files/" + FLAT_KEY);

        assertEquals(200, response.getStatus());
        assertArrayEquals(objects.get(FLAT_KEY), response.getContentAsByteArray());
        assertEquals("image/png", response.getContentType());
        assertEquals("\"" + FLAT_KEY + "\"", response.getHeader("ETag"));
        assertEquals("public, max-age=31536000, immutable", response.getHeader("Cache-Control"));
        assertEquals(objects.get(FLAT_KEY).length, response.getContentLength());
    }

    @Test
    @DisplayName("按 项目/任务 分目录存放的图片附件 URL 与本地模式一致，可正常读取")
    void servesNestedAttachmentImage() throws Exception {
        MockHttpServletResponse response = get("/api/v1/upload/files/" + NESTED_KEY);

        assertEquals(200, response.getStatus());
        assertArrayEquals(objects.get(NESTED_KEY), response.getContentAsByteArray());
        assertEquals("image/jpeg", response.getContentType());
    }

    @Test
    @DisplayName("路径穿越形式一律 404，且不向存储层发起读取")
    void rejectsPathTraversal() throws Exception {
        MockHttpServletResponse response = get("/api/v1/upload/files/../" + NESTED_KEY);

        assertEquals(404, response.getStatus());
        assertEquals(0, accessedKeys.size());
    }

    @Test
    @DisplayName("非图片扩展名拒绝：公开接口不得读到附件对象")
    void rejectsNonImageExtension() throws Exception {
        objects.put("7/42/" + UUID_NAME + ".pdf", "secret".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = get("/api/v1/upload/files/7/42/" + UUID_NAME + ".pdf");

        assertEquals(404, response.getStatus());
        assertEquals(0, accessedKeys.size());
    }

    @Test
    @DisplayName("对象不存在返回 404，不泄露桶内其它信息")
    void returnsNotFoundForMissingObject() throws Exception {
        MockHttpServletResponse response = get("/api/v1/upload/files/" + UUID_NAME + "abcd.png");

        assertEquals(404, response.getStatus());
    }

    @Test
    @DisplayName("命中 ETag 返回 304 空响应体")
    void returnsNotModifiedWhenETagMatches() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/upload/files/" + FLAT_KEY);
        request.setRequestURI("/api/v1/upload/files/" + FLAT_KEY);
        request.addHeader("If-None-Match", "\"" + FLAT_KEY + "\"");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.getImage(request, response);

        assertEquals(304, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    private MockHttpServletResponse get(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        // MockHttpServletRequest 默认按 servletPath+pathInfo 推导 requestURI，这里显式指定完整 URI
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.getImage(request, response);
        return response;
    }

    /** 内存假存储：只实现接口契约，并记录真实被访问的 key 以便断言白名单生效 */
    private class FakeStorage implements FileStorage {

        @Override
        public void store(String key, InputStream content) throws IOException {
            objects.put(key, content.readAllBytes());
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public InputStream open(String key) throws IOException {
            accessedKeys.add(key);
            byte[] content = objects.get(key);
            if (content == null) {
                throw new IOException("object not found: " + key);
            }
            return new ByteArrayInputStream(content);
        }

        @Override
        public long size(String key) {
            accessedKeys.add(key);
            byte[] content = objects.get(key);
            return content == null ? -1 : content.length;
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public StorageStats stats() {
            return new StorageStats(objects.size(), objects.values().stream().mapToLong(v -> v.length).sum());
        }
    }
}
