-- =====================================================
-- V4: 项目分类
-- 1) 新建项目分类表（全局共享，名称唯一）
-- 2) project 表新增 category_id 列（NULL=未分类）
-- =====================================================

CREATE TABLE project_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目分类';

ALTER TABLE project ADD COLUMN category_id BIGINT NULL COMMENT '项目分类ID(NULL=未分类)';
