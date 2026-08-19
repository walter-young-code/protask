package com.protask.dto;

import lombok.Data;

/**
 * 管理后台注销用户请求（字段可选）。
 */
@Data
public class AdminUserDeleteRequest {

    /**
     * 项目接管人用户ID：null 时自动接管
     * （项目内其他 admin 成员，无则转移给执行注销的管理员）
     */
    private Long takeoverUserId;
}
