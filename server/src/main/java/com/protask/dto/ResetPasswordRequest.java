package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台重置密码请求。
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "{validation.password.notBlank}")
    @Size(min = 8, max = 50, message = "{validation.password.size}")
    private String password;
}
