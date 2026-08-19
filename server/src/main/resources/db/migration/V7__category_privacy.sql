-- =====================================================
-- V7: 项目分类私有化
-- 分类仅创建者可见可管理：名称唯一性范围从全局收窄到同一创建者内
-- =====================================================

ALTER TABLE project_category DROP INDEX uk_category_name;
ALTER TABLE project_category ADD UNIQUE KEY uk_category_name_owner (name, created_by);
