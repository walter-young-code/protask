package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知实体：当前仅评论 @提及(mention)一种类型。
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人（用户 ID） */
    private Long userId;

    /** 通知类型: mention */
    private String type;

    /** 关联项目 ID */
    private Long projectId;

    /** 关联任务（卡片块）ID */
    private Long taskId;

    /** 关联评论块 ID */
    private Long commentId;

    /** 操作人（用户 ID） */
    private Long actorId;

    /** 评论摘要（截断存储） */
    private String snippet;

    /** 已读状态: 0 未读, 1 已读 */
    private Integer isRead;

    private LocalDateTime createAt;
}
