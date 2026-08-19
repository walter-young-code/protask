-- =====================================================
-- ProTask 初始化数据库结构
-- 实体：用户(sys_user) / 项目(project) / 项目成员(project_member) / 任务(task)
-- =====================================================

-- 用户表
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
    nickname    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    email       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像 URL',
    role        VARCHAR(20)  NOT NULL DEFAULT 'user' COMMENT '系统角色: admin/user',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1 启用, 0 禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

-- 项目表
CREATE TABLE project (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(100) NOT NULL COMMENT '项目名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '项目描述',
    owner_id    BIGINT       NOT NULL COMMENT '项目负责人(用户ID)',
    status      VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态: active/archived',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '项目表';

-- 项目成员表（多对多关系）
CREATE TABLE project_member (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id BIGINT      NOT NULL COMMENT '项目ID',
    user_id    BIGINT      NOT NULL COMMENT '用户ID',
    role       VARCHAR(20) NOT NULL DEFAULT 'member' COMMENT '项目角色: owner/admin/member',
    joined_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_user (project_id, user_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '项目成员表';

-- 任务表
CREATE TABLE task (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id  BIGINT       NOT NULL COMMENT '所属项目ID',
    title       VARCHAR(200) NOT NULL COMMENT '任务标题',
    description TEXT         DEFAULT NULL COMMENT '任务描述',
    status      VARCHAR(20)  NOT NULL DEFAULT 'todo' COMMENT '状态: todo/in_progress/done',
    priority    VARCHAR(20)  NOT NULL DEFAULT 'medium' COMMENT '优先级: low/medium/high/urgent',
    assignee_id BIGINT       DEFAULT NULL COMMENT '负责人(用户ID)',
    creator_id  BIGINT       NOT NULL COMMENT '创建人(用户ID)',
    due_date    DATETIME     DEFAULT NULL COMMENT '截止时间',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_project (project_id),
    KEY idx_assignee (assignee_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '任务表';
