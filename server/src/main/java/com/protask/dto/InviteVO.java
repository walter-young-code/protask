package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 注册邀请视图：邀请凭证与完整注册链接。
 * inviteUrl 基于请求 Origin 拼接，供复制邀请链接用。
 */
@Data
public class InviteVO {

    /** 邀请凭证（注册链接携带） */
    private String token;

    /** 完整邀请链接（格式：{origin}/register?invite={token}） */
    private String inviteUrl;

    /** 过期时间 */
    private LocalDateTime expiresAt;
}
