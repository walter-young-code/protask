package com.protask.dto;

import lombok.Data;

/**
 * 站点公开设置视图：登录页与顶栏品牌展示（应用名称 + LOGO）。
 */
@Data
public class SiteSettingsVO {

    /** 应用名称（未配置时回退 ProTask） */
    private String name;

    /** 应用 LOGO 图片 URL（未配置时为 null，前端回退内置默认 LOGO） */
    private String logo;
}
