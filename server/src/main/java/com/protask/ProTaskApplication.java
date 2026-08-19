package com.protask;

import com.protask.config.TimeZoneConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ProTask 任务管理系统启动类。
 */
@SpringBootApplication
@MapperScan("com.protask.mapper")
public class ProTaskApplication {

    public static void main(String[] args) {
        // 必须在 Spring 容器启动前设置默认时区：ZoneId.systemDefault() 存在一次性缓存，
        // JDBC 驱动等组件若在设置前触发缓存，后续 LocalDateTime.now() 仍会返回旧时区时间
        TimeZoneConfig.applySystemDefault();
        SpringApplication.run(ProTaskApplication.class, args);
    }
}
