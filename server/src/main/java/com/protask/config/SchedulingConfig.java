package com.protask.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：存储巡检等周期性作业。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
