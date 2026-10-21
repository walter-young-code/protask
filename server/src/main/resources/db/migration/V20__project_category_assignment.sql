-- =====================================================
-- V20: 项目分类归属按用户独立
-- 1) 新建归类表：每个用户对自己的侧边栏归类，互不影响
-- 2) 迁移历史数据：仅项目创建者即分类创建者的干净数据
-- 3) 删除 project.category_id（全局单值归属无法表达多人视角）
-- =====================================================

CREATE TABLE project_category_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '归类所属用户',
    project_id BIGINT NOT NULL COMMENT '项目ID',
    category_id BIGINT NOT NULL COMMENT '分类ID(必须属于 user_id)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_project (user_id, project_id),
    KEY idx_user_category (user_id, category_id),
    KEY idx_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目分类归属(按用户)';

-- 只迁移项目创建者就是分类创建者的数据；
-- 历史复制项目携带他人分类的污染数据不迁移，迁移后显示为未分类
INSERT INTO project_category_assignment (user_id, project_id, category_id)
SELECT p.created_by, p.id, p.category_id
FROM project p
JOIN project_category c ON c.id = p.category_id AND c.created_by = p.created_by
WHERE p.delete_at IS NULL AND p.is_template = 0;

ALTER TABLE project DROP COLUMN category_id;
