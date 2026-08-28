package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台创建用户请求。
 */
@Data
public class AdminUserCreateRequest {

    @NotBlank(message = "{validation.username.notBlank}")
    @Size(min = 3, max = 50, message = "{validation.username.size}")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "{validation.username.pattern}")
    private String username;

    @NotBlank(message = "{validation.password.notBlank}")
    @Size(min = 8, max = 50, message = "{validation.password.size}")
    private String password;

    @Size(max = 50, message = "{validation.nickname.size}")
    private String nickname;

    @Size(max = 50, message = "{validation.firstName.size}")
    private String firstName;

    @Size(max = 50, message = "{validation.lastName.size}")
    private String lastName;

    @Size(max = 100, message = "{validation.email.size}")
    private String email;

    /** 系统角色: admin/user，缺省 user */
    private String role;

    /** 状态: 1 启用, 0 禁用，缺省启用 */
    private Integer status;

    /** 所属团队 ID：>0 指定团队，0/null 表示无团队 */
    private Long teamId;
}
