package com.protask.config;

import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认证接口限流拦截器：按客户端 IP 对登录/注册接口做固定窗口限流，
 * 防御暴力破解与批量注册。窗口内超限返回 429，窗口滚动后自动恢复。
 */
@Component
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    /** 固定窗口时长（毫秒） */
    private static final long WINDOW_MS = 60_000L;

    /** 惰性清理阈值：计数表超过该大小时顺带清理过期窗口 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    /** 登录接口单 IP 每分钟最大次数 */
    @Value("${protask.auth.rate-limit.login:10}")
    private int loginLimit;

    /** 注册接口单 IP 每分钟最大次数 */
    @Value("${protask.auth.rate-limit.register:5}")
    private int registerLimit;

    /** IP -> 当前窗口计数器（窗口起点 + 次数） */
    private final Map<String, Window> counters = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        int limit;
        if (path.endsWith("/api/v1/auth/login")) {
            limit = loginLimit;
        } else if (path.endsWith("/api/v1/auth/register")) {
            limit = registerLimit;
        } else {
            return true;
        }
        if (allow(clientIp(request), limit)) {
            return true;
        }
        throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "auth.rateLimited");
    }

    /** 固定窗口计数：窗口过期重置，窗口内超限拒绝 */
    private boolean allow(String ip, int limit) {
        if (limit <= 0) {
            return true;
        }
        if (counters.size() > CLEANUP_THRESHOLD) {
            cleanup();
        }
        long now = System.currentTimeMillis();
        Window window = counters.compute(ip, (key, existing) ->
                existing != null && now - existing.windowStart < WINDOW_MS ? existing : new Window(now));
        return window.count.incrementAndGet() <= limit;
    }

    /** 清理过期窗口，防止计数表无限增长 */
    private void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Window>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().windowStart >= WINDOW_MS) {
                it.remove();
            }
        }
    }

    /** 客户端真实 IP（优先取反向代理头） */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 单个 IP 的窗口计数 */
    private static final class Window {
        private final long windowStart;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
