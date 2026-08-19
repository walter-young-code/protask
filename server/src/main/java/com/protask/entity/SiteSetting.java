package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 站点设置实体：键值对存储应用名称、LOGO 等品牌信息。
 */
@Data
@TableName("site_setting")
public class SiteSetting {

    /** 设置键（如 site_name / site_logo） */
    @TableId(value = "setting_key", type = IdType.INPUT)
    private String settingKey;

    /** 设置值 */
    private String settingValue;
}
