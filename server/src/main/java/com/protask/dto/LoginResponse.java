package com.protask.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应：JWT token + 当前用户信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /** JWT token */
    private String token;

    /** 当前用户信息 */
    private UserVO user;
}
