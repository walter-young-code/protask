package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.AdminUserCreateRequest;
import com.protask.dto.AdminUserDeleteRequest;
import com.protask.dto.AdminUserQuery;
import com.protask.dto.AdminUserUpdateRequest;
import com.protask.dto.AdminUserVO;
import com.protask.dto.PageResult;
import com.protask.dto.ResetPasswordRequest;
import com.protask.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台用户接口（仅系统管理员可访问）。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /** 分页查询用户（关键字 + 状态过滤） */
    @GetMapping
    public Result<PageResult<AdminUserVO>> page(AdminUserQuery query) {
        return Result.ok(adminUserService.page(StpUtil.getLoginIdAsLong(), query));
    }

    /** 创建用户 */
    @PostMapping
    public Result<AdminUserVO> create(@Valid @RequestBody AdminUserCreateRequest request) {
        return Result.ok(adminUserService.create(StpUtil.getLoginIdAsLong(), request));
    }

    /** 更新用户（昵称/邮箱/角色/状态，用户名不可修改） */
    @PutMapping("/{id}")
    public Result<AdminUserVO> update(@PathVariable Long id, @Valid @RequestBody AdminUserUpdateRequest request) {
        return Result.ok(adminUserService.update(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 重置密码 */
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        adminUserService.resetPassword(StpUtil.getLoginIdAsLong(), id, request.getPassword());
        return Result.ok();
    }

    /** 注销用户（不可逆）：可指定项目接管人，历史数据保留 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id,
                                   @RequestBody(required = false) AdminUserDeleteRequest request) {
        adminUserService.deleteUser(StpUtil.getLoginIdAsLong(), id,
                request != null ? request.getTakeoverUserId() : null);
        return Result.ok();
    }
}
