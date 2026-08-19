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
}
