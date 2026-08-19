package com.protask.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 统一时区配置：将 JVM 默认时区设置为配置时区（固定 UTC）。
 * 所有 LocalDateTime.now() 调用点（任务创建/更新/软删除、邀请过期等）
 * 据此产生 UTC 时间，保证跨时区部署下时间戳存储口径一致。
 * 注意：首次设置发生在 main() 中（见 applySystemDefault），
 * 避免 ZoneId.systemDefault() 缓存被其他组件以旧时区提前触发。
 */
@Configuration
public class TimeZoneConfig {

    /** 统一时区（跨时区一致性要求固定 UTC） */
    private static final String DEFAULT_TIMEZONE = "UTC";

    @Value("${protask.timezone:UTC}")
    private String timezone;

    /**
     * 容器启动前应用默认时区：优先取 PROTASK_TIMEZONE 环境变量/同名系统属性，
     * 未配置时使用 UTC。
     */
    public static void applySystemDefault() {
        String tz = System.getProperty("PROTASK_TIMEZONE",
                System.getenv().getOrDefault("PROTASK_TIMEZONE", DEFAULT_TIMEZONE));
        TimeZone.setDefault(TimeZone.getTimeZone(tz));
    }

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone(timezone));
    }
}
