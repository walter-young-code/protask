package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务视图对象（含创建人昵称）。
 */
@Data
public class TaskVO {

    private Long id;

    private Long projectId;

    /** 所属项目名称（仅全局搜索场景填充） */
    private String projectName;

    private String title;

    /** 任务图标（emoji） */
    private String icon;

    private String description;

    private Long creatorId;

    /** 创建人昵称 */
    private String creatorName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 卡片全部动态属性（按项目 cardProperties 模板定义） */
    private Map<String, Object> properties;
}
