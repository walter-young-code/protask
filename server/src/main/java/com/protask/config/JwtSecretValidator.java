package com.protask.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * JWT 签名密钥启动校验：密钥为内置默认值或空值时输出安全告警。
 * 默认值仅用于本地开发；容器部署（docker-compose）已强制要求显式注入 PROTASK_JWT_SECRET。
 */
@Slf4j
@Component
public class JwtSecretValidator implements InitializingBean {

    /** 仓库中公开的默认密钥（出现在 application.yml / docker-compose 示例中），不可用于生产 */
    private static final Set<String> INSECURE_SECRETS = Set.of(
            "protask-dev-jwt-secret-key-2026",
            "protask-docker-jwt-secret-change-me");

    private final String jwtSecretKey;

    public JwtSecretValidator(@Value("${sa-token.jwt-secret-key:}") String jwtSecretKey) {
        this.jwtSecretKey = jwtSecretKey;
    }

    @Override
    public void afterPropertiesSet() {
        if (jwtSecretKey == null || jwtSecretKey.isBlank() || INSECURE_SECRETS.contains(jwtSecretKey)) {
            log.warn("""
                    ============================================================
                    安全警告：JWT 签名密钥为内置默认值或空值，任何人都可伪造登录 token。
                    请通过环境变量 PROTASK_JWT_SECRET 注入随机长字符串，
                    生成示例：openssl rand -base64 48
                    ============================================================""");
        }
    }
}
