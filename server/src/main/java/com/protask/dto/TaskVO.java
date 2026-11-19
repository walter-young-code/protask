package com.protask.dto;

import lombok.Data;

import java.time.LocalDate;
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

    /** 附件数量（任务下附件记录数） */
    private Long attachmentCount;

    /** 父任务ID（仅 WBS 项目返回，根任务为 null） */
    private Long parentTaskId;

    /** WBS 编码（仅 WBS 项目按树序自动编号：1 / 1.1 / 1.2，只读） */
    private String wbs;

    /** 直接子任务数（仅 WBS 项目返回） */
    private Integer childCount;

    /** 里程碑标记（false=普通任务,true=里程碑） */
    private Boolean milestone;

    /** 计划模式（manual=手动/auto=自动，仅 WBS 项目有意义） */
    private String scheduleMode;

    /** 显式工期（自然日含首日），null=按起止日期推导 */
    private Integer durationDays;

    /** 自动计划开始约束（不得早于），null=纯前置驱动 */
    private LocalDate startConstraint;
}
