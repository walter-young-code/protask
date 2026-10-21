package com.protask.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Sa-Token 配置：登录拦截规则 + JWT 模式。
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final DisabledUserInterceptor disabledUserInterceptor;
    private final AuthRateLimitInterceptor authRateLimitInterceptor;

    /** 图片上传目录（与 UploadController 一致，支持 protask.upload-dir 配置覆盖） */
    @Value("${protask.upload-dir:uploads}")
    private String uploadDir;

    /** 存储后端：local 走本地磁盘静态映射，oss 由 PublicImageController 提供同名接口 */
    @Value("${protask.storage.backend:local}")
    private String storageBackend;

    /**
     * 启用 JWT 模式（Simple 模式：token 为 JWT，会话数据仍保存在服务端）。
     */
    @Bean
    public StpLogic getStpLogic() {
        return new StpLogicJwtForSimple();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录/注册接口按 IP 限流，防御暴力破解与批量注册（先于登录拦截执行）
        registry.addInterceptor(authRateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/login", "/api/v1/auth/register");
        // 所有 /api/** 接口默认需要登录，登录/注册、站点公开设置与上传的静态图片放行
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        // 注册页校验邀请链接有效性不需要登录
                        "/api/v1/auth/invites/validate",
                        // 站点公开设置：登录页/顶栏品牌展示不需要登录
                        "/api/v1/site/settings",
                        // 日历订阅：Outlook 等客户端无法携带登录凭证，凭 URL 内嵌的订阅 token 匿名拉取
                        "/api/v1/public/calendars/**",
                        // 图片为公开资源：Markdown 中的 <img> 标签不会携带 Authorization 头
                        "/api/v1/upload/files/**"
                );
        // 禁用用户的会话立即失效（未登录请求直接放行，与登录拦截规则解耦）
        registry.addInterceptor(disabledUserInterceptor).addPathPatterns("/api/**");
    }

    /** 上传图片的静态映射：/api/v1/upload/files/** -> 本地上传目录（仅 local 后端） */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!"local".equalsIgnoreCase(storageBackend)) {
            // OSS 模式下磁盘上不存在这些文件，改由 PublicImageController 从对象存储流式返回
            return;
        }
        String absolute = Paths.get(uploadDir).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/api/v1/upload/files/**")
                .addResourceLocations("file:" + absolute + "/");
    }
}
