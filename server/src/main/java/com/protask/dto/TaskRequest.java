package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建/更新任务请求。
 */
@Data
public class TaskRequest {

    @NotBlank(message = "{validation.taskTitle.notBlank}")
    @Size(max = 200, message = "{validation.taskTitle.size}")
    private String title;

    /** 任务图标（emoji），null 表示不修改，空串表示移除 */
    @Size(max = 32, message = "{validation.taskIcon.size}")
    private String icon;

    private String description;

    /** 动态属性(按项目 cardProperties 定义)，整体替换卡片 properties */
    private Map<String, Object> properties;

    /**
     * 父任务ID（仅 WBS 项目生效）：创建时 null=根任务；
     * 更新时 null=不修改，0=设为根任务，其余=指定父任务（防环校验）。
     */
    private Long parentTaskId;

    /** 里程碑标记，null 表示不修改（与 icon 语义一致） */
    private Boolean milestone;

    /** 计划模式（仅 WBS 项目生效）：manual/auto，null 表示不修改 */
    private String scheduleMode;

    /** 显式工期（自然日含首日，1~3650），null 表示不修改；里程碑忽略 */
    private Integer durationDays;

    /** true=清除自动计划开始约束（恢复纯前置驱动），null/false 不修改 */
    private Boolean clearConstraint;
}
