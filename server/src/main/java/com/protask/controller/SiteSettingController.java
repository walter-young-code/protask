package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.SiteSettingsRequest;
import com.protask.dto.SiteSettingsVO;
import com.protask.service.SiteSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点设置接口：品牌信息（应用名称/LOGO）读取与更新。
 */
@RestController
@RequestMapping("/api/v1/site")
@RequiredArgsConstructor
public class SiteSettingController {

    private final SiteSettingService siteSettingService;

    /** 站点公开设置：登录页/顶栏品牌展示（无需登录） */
    @GetMapping("/settings")
    public Result<SiteSettingsVO> getSettings() {
        return Result.ok(siteSettingService.getPublicSettings());
    }

    /** 更新站点设置（仅系统管理员，未登录请求由登录拦截/异常兜底拒绝） */
    @PutMapping("/settings")
    public Result<SiteSettingsVO> updateSettings(@RequestBody SiteSettingsRequest request) {
        StpUtil.checkLogin();
        return Result.ok(siteSettingService.updateSettings(StpUtil.getLoginIdAsLong(), request));
    }
}
