package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 注册邀请凭证实体：关闭公开注册后，用户须持有效邀请链接方可注册。
 */
@Data
@TableName("invite_token")
public class InviteToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邀请凭证（随机串，注册链接携带） */
    private String token;

    /** 邀请人（用户 ID） */
    private Long invitedBy;

    /** 状态: 0 未使用, 1 已使用 */
    private Integer status;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 使用时间（为 null 表示未使用） */
    private LocalDateTime usedAt;

    /** 注册用户 ID（为 null 表示未使用） */
    private Long usedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
