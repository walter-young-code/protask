package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台用户视图对象（脱敏，不含密码，含状态与时间信息）。
 */
@Data
public class AdminUserVO {

    private Long id;

    private String username;

    private String nickname;

    /** 名（First Name） */
    private String firstName;

    /** 姓（Last Name） */
    private String lastName;

    /** 全名：First + Last 拼接，未填写时为空 */
    private String fullName;

    private String email;

    /** 头像 URL */
    private String avatar;

    /** 所属团队 ID（可空） */
    private Long teamId;

    /** 所属团队名称（可空） */
    private String teamName;

    /** 系统角色: admin/user */
    private String role;

    /** 状态: 1 启用, 0 禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
