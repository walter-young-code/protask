package com.protask.dto;

import lombok.Data;

import java.util.List;

/**
 * 仪表盘聚合视图：跨项目统计数字 + 分类任务列表（各列表最多 20 条）。
 */
@Data
public class DashboardVO {

    /** 可见项目内任务总数 */
    private long totalTasks;

    /** 我参与的任务数（我创建或我负责） */
    private long myTasks;

    /** 紧急任务数（内置优先级 P0） */
    private long urgentTasks;

    /** 逾期任务数（截止日期早于今天且未完成） */
    private long overdueTasks;

    /** 即将到期任务数（今天起 7 天内截止且未完成） */
    private long dueSoonTasks;

    /** 已完成任务数（内置状态 done） */
    private long doneTasks;

    /** 我参与的任务列表（按更新时间倒序） */
    private List<DashboardTaskItemVO> myTaskList = List.of();

    /** 紧急任务列表（按更新时间倒序） */
    private List<DashboardTaskItemVO> urgentList = List.of();

    /** 逾期任务列表（按截止日期升序） */
    private List<DashboardTaskItemVO> overdueList = List.of();

    /** 即将到期任务列表（按截止日期升序） */
    private List<DashboardTaskItemVO> dueSoonList = List.of();
}
