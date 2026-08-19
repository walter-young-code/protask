package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "{validation.username.notBlank}")
    private String username;

    @NotBlank(message = "{validation.password.notBlank}")
    private String password;
}
