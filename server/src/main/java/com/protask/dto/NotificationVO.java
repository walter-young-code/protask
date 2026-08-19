package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知视图对象（附带操作人昵称与任务标题，便于前端直接展示）。
 */
@Data
public class NotificationVO {

    private Long id;

    /** 通知类型: mention */
    private String type;

    private Long projectId;

    private Long taskId;

    private Long commentId;

    private Long actorId;

    /** 操作人昵称 */
    private String actorName;

    /** 关联任务标题 */
    private String taskTitle;

    /** 评论摘要 */
    private String snippet;

    /** 是否已读 */
    private Boolean isRead;

    private LocalDateTime createAt;
}
