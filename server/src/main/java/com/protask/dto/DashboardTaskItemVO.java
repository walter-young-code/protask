package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仪表盘任务列表项（跨项目轻量字段，点击可跳转项目任务详情）。
 */
@Data
public class DashboardTaskItemVO {

    private Long id;

    private Long projectId;

    /** 所属项目名称 */
    private String projectName;

    private String title;

    /** 任务图标（emoji） */
    private String icon;

    /** 截止日期（YYYY-MM-DD，未设置为 null） */
    private String dueDate;

    /** 创建人昵称 */
    private String creatorName;

    private LocalDateTime updatedAt;
}
