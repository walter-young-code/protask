-- =====================================================
-- V9: 新增团队管理与用户团队字段
-- team 表管理团队；sys_user 增加 team_id 关联所属团队（可空）
-- =====================================================

-- 团队表
CREATE TABLE team (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(50)  NOT NULL COMMENT '团队名称',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '团队表';

-- 用户表增加所属团队字段
ALTER TABLE sys_user ADD COLUMN team_id BIGINT DEFAULT NULL COMMENT '所属团队ID';
CREATE INDEX idx_team ON sys_user (team_id);
