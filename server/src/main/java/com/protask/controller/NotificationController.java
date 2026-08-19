package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.NotificationVO;
import com.protask.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内通知接口（仅当前登录用户自己的通知）。
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 查询我的通知列表（按时间倒序，默认最近 50 条） */
    @GetMapping
    public Result<List<NotificationVO>> list(@RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
                                             @RequestParam(required = false, defaultValue = "50") int limit) {
        return Result.ok(notificationService.listMy(StpUtil.getLoginIdAsLong(), unreadOnly, limit));
    }

    /** 未读通知数（顶栏铃铛角标） */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(notificationService.unreadCount(StpUtil.getLoginIdAsLong()));
    }

    /** 标记单条通知已读 */
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    /** 标记全部通知已读 */
    @PutMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead(StpUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
