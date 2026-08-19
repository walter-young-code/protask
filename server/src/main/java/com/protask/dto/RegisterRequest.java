package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "{validation.username.notBlank}")
    @Size(min = 3, max = 50, message = "{validation.username.size}")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "{validation.username.pattern}")
    private String username;

    @NotBlank(message = "{validation.password.notBlank}")
    @Size(min = 6, max = 50, message = "{validation.password.size}")
    private String password;

    @Size(max = 50, message = "{validation.nickname.size}")
    private String nickname;

    @Size(max = 50, message = "{validation.firstName.size}")
    private String firstName;

    @Size(max = 50, message = "{validation.lastName.size}")
    private String lastName;

    @Size(max = 100, message = "{validation.email.size}")
    private String email;

    @NotBlank(message = "{validation.inviteToken.notBlank}")
    @Size(max = 64, message = "{validation.inviteToken.size}")
    private String inviteToken;
}
