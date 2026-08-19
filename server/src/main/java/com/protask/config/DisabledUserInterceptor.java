package com.protask.config;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.entity.User;
import com.protask.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户会话拦截器：账号被禁用后其已签发的 token 立即失效（强制登出并返回 403）；
 * 修改/重置密码后密码版本号与 token 载荷不一致时同样强制登出（返回 401）。
 * 未登录请求直接放行（登录/注册接口及公开静态资源不在此列）。
 * <p>
 * 用户状态带短 TTL 缓存，避免每个请求都查库；禁用/改密操作最多延迟 TTL 时长生效。
 */
@Component
@RequiredArgsConstructor
public class DisabledUserInterceptor implements HandlerInterceptor {

    /** 状态缓存 TTL（毫秒）：禁用账号后旧 token 在该时长内必然失效 */
    private static final long CACHE_TTL_MS = 3_000L;

    private final UserMapper userMapper;

    /** 用户ID -> 状态缓存（是否有效 + 密码版本号 + 过期时间） */
    private final Map<Long, CacheEntry> statusCache = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (StpUtil.isLogin()) {
            Long userId = StpUtil.getLoginIdAsLong();
            CacheEntry state = queryState(userId);
            if (!state.active) {
                statusCache.remove(userId);
                StpUtil.logout();
                throw new BusinessException(ResultCode.FORBIDDEN, "auth.account.disabled");
            }
            // 密码版本号不匹配（修改/重置密码后签发）的旧 token 立即失效
            // 注意：Sa-Token 解析 JWT 载荷返回 hutool NumberWithFormat，不能与 Integer 直接用 equals 比较
            Object tokenVersion = StpUtil.getExtra("pwdVersion");
            if (tokenVersion != null && state.passwordVersion != null
                    && Long.parseLong(tokenVersion.toString()) != state.passwordVersion.longValue()) {
                statusCache.remove(userId);
                StpUtil.logout();
                throw new BusinessException(ResultCode.UNAUTHORIZED, "auth.password.changed");
            }
        }
        return true;
    }

    /** 查询用户状态（短 TTL 缓存，过期回源查询） */
    private CacheEntry queryState(Long userId) {
        long now = System.currentTimeMillis();
        CacheEntry entry = statusCache.get(userId);
        if (entry != null && now < entry.expireAt) {
            return entry;
        }
        User user = userMapper.selectById(userId);
        boolean active = user != null && user.getStatus() != null && user.getStatus() == 1;
        statusCache.put(userId, new CacheEntry(active, user != null ? user.getPasswordVersion() : null,
                now + CACHE_TTL_MS));
        return statusCache.get(userId);
    }

    /** 单用户状态缓存条目 */
    private record CacheEntry(boolean active, Integer passwordVersion, long expireAt) {
    }
}
