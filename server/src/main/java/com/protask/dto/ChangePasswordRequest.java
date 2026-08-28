package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求。
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "{validation.password.notBlank}")
    private String oldPassword;

    @NotBlank(message = "{validation.password.notBlank}")
    @Size(min = 8, max = 50, message = "{validation.password.size}")
    private String newPassword;
}
