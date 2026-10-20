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
import java.util.concurrent.atomic.AtomicLong;

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

    /** 是否信任反向代理追加的 X-Forwarded-For（仅当请求必经可信代理时开启） */
    @Value("${protask.auth.rate-limit.trust-proxy:false}")
    private boolean trustProxy;

    /** IP -> 当前窗口计数器（窗口起点 + 次数） */
    private final Map<String, Window> counters = new ConcurrentHashMap<>();

    /** 上次定期清理时间（毫秒），避免计数表随伪造 IP 无限增长 */
    private final AtomicLong lastCleanupAt = new AtomicLong();

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
        long now = System.currentTimeMillis();
        maybeCleanup(now);
        Window window = counters.compute(ip, (key, existing) ->
                existing != null && now - existing.windowStart < WINDOW_MS ? existing : new Window(now));
        return window.count.incrementAndGet() <= limit;
    }

    /** 定期清理过期窗口：每分钟最多执行一次，同时兼顾计数表过大时的强制清理 */
    private void maybeCleanup(long now) {
        long last = lastCleanupAt.get();
        if (now - last >= WINDOW_MS) {
            if (lastCleanupAt.compareAndSet(last, now)) {
                cleanup(now);
            }
            return;
        }
        if (counters.size() > CLEANUP_THRESHOLD) {
            cleanup(now);
        }
    }

    /** 清理过期窗口，防止计数表无限增长 */
    private void cleanup(long now) {
        Iterator<Map.Entry<String, Window>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().windowStart >= WINDOW_MS) {
                it.remove();
            }
        }
    }

    /**
     * 客户端真实 IP：
     * - 直连部署（trust-proxy=false）使用 remoteAddr，避免客户端伪造 X-Forwarded-For 绕过限流；
     * - 反向代理部署（trust-proxy=true）取 X-Forwarded-For 末尾地址：
     *   nginx 的 $proxy_add_x_forwarded_for 会把真实客户端地址追加到末尾，
     *   客户端自带的伪造值只可能出现在前面。
     */
    private String clientIp(HttpServletRequest request) {
        if (!trustProxy) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
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
