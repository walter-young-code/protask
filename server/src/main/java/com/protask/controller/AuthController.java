package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.ChangePasswordRequest;
import com.protask.dto.InviteVO;
import com.protask.dto.LoginRequest;
import com.protask.dto.LoginResponse;
import com.protask.dto.RegisterRequest;
import com.protask.dto.UserVO;
import com.protask.entity.InviteToken;
import com.protask.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 用户注册 */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /** 用户登录，返回 JWT token */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /** 生成一次性注册邀请链接（需登录） */
    @PostMapping("/invites")
    public Result<InviteVO> createInvite() {
        return Result.ok(authService.createInvite(StpUtil.getLoginIdAsLong()));
    }

    /** 校验邀请链接凭证是否有效（注册页加载时调用，公开） */
    @GetMapping("/invites/validate")
    public Result<InviteVO> validateInvite(@RequestParam String token) {
        InviteToken invite = authService.validateInvite(token);
        return Result.ok(authService.toInviteVO(invite));
    }

    /** 登出 */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    /** 当前登录用户信息 */
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.currentUser());
    }

    /** 修改当前用户密码（校验旧密码） */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(StpUtil.getLoginIdAsLong(), request);
        return Result.ok();
    }
}
