-- 站点设置键值表：存储应用名称、LOGO 等品牌信息（管理后台可修改）
CREATE TABLE site_setting (
    setting_key   VARCHAR(64)  NOT NULL COMMENT '设置键',
    setting_value VARCHAR(500) NOT NULL COMMENT '设置值',
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='站点设置';

-- 种子数据：默认品牌（LOGO 为空表示使用内置默认 LOGO）
INSERT INTO site_setting (setting_key, setting_value)
VALUES ('site_name', 'ProTask'),
       ('site_logo', '');
