package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.StorageCleanupVO;
import com.protask.dto.StorageStatsVO;
import com.protask.service.StorageAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台存储运维接口（仅系统管理员）：用量统计与孤儿图片清理。
 */
@RestController
@RequestMapping("/api/v1/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {

    private final StorageAdminService storageAdminService;

    /** 存储用量 + 孤儿图片统计 */
    @GetMapping
    public Result<StorageStatsVO> stats() {
        return Result.ok(storageAdminService.stats(StpUtil.getLoginIdAsLong()));
    }

    /** 清理无引用且超过保留期的孤儿图片 */
    @DeleteMapping("/orphans")
    public Result<StorageCleanupVO> cleanupOrphans() {
        return Result.ok(storageAdminService.cleanupOrphans(StpUtil.getLoginIdAsLong()));
    }
}
