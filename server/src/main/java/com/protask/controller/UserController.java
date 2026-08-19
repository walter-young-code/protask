package com.protask.controller;

import com.protask.common.Result;
import com.protask.dto.UserVO;
import com.protask.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户接口。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 查询所有启用状态的用户（用于任务分配下拉） */
    @GetMapping
    public Result<List<UserVO>> listUsers() {
        return Result.ok(userService.listActiveUsers());
    }
}
