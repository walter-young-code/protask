package com.protask.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台更新用户请求（用户名不可修改）。
 */
@Data
public class AdminUserUpdateRequest {

    @Size(max = 50, message = "{validation.nickname.size}")
    private String nickname;

    @Size(max = 50, message = "{validation.firstName.size}")
    private String firstName;

    @Size(max = 50, message = "{validation.lastName.size}")
    private String lastName;

    @Size(max = 100, message = "{validation.email.size}")
    private String email;

    /** 系统角色: admin/user */
    private String role;

    /** 状态: 1 启用, 0 禁用 */
    private Integer status;

    /** 所属团队 ID：null 不修改，0 清除团队，>0 指定团队 */
    private Long teamId;
}
