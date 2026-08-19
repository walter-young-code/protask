-- =====================================================
-- V6: 项目模板库
-- project 表新增 is_template 标记：模板项目不产生成员关系，
-- 仅通过 /api/v1/templates 接口读取，并从模板复制创建新项目。
-- =====================================================

ALTER TABLE project
    ADD COLUMN is_template TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否项目模板: 0 否, 1 是' AFTER properties;
