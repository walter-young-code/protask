package com.protask.dto;

import lombok.Data;

/**
 * 站点设置更新请求（仅系统管理员）。
 */
@Data
public class SiteSettingsRequest {

    /** 应用名称（必填，最长 50 字符） */
    private String name;

    /** 应用 LOGO 图片 URL（空表示恢复内置默认 LOGO） */
    private String logo;
}
