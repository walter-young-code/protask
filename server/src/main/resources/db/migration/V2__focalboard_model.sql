-- =====================================================
-- V2: 对齐 Focalboard 白板/任务模型
-- 1) project 表新增类型/最低角色/动态属性模板/审计与软删除字段
-- 2) project_member 角色迁移为 viewer/commenter/editor/admin
-- 3) 新建统一块模型表 block（card/view/text/comment）
-- 4) task 数据迁入 block 后删除 task 表
-- =====================================================

-- 1. project 表扩展 ------------------------------------------------------

-- 项目类型: O 公开(非成员可读) / P 私有
ALTER TABLE project
    ADD COLUMN type CHAR(1) NOT NULL DEFAULT 'P' COMMENT '项目类型: O 公开, P 私有' AFTER description;

-- 成员加入项目时自动获得的最低角色: ''/viewer/commenter/editor/admin
ALTER TABLE project
    ADD COLUMN minimum_role VARCHAR(20) NOT NULL DEFAULT '' COMMENT '加入即获得的最低角色' AFTER type;

ALTER TABLE project
    ADD COLUMN icon VARCHAR(255) DEFAULT NULL COMMENT '项目图标' AFTER minimum_role;

-- 任务(卡片)动态属性定义模板，内置 status/priority/assignee/due_date
ALTER TABLE project
    ADD COLUMN card_properties JSON DEFAULT NULL COMMENT '卡片动态属性定义模板' AFTER icon;

-- 项目自身的扩展属性
ALTER TABLE project
    ADD COLUMN properties JSON DEFAULT NULL COMMENT '项目扩展属性' AFTER card_properties;

-- 审计字段与软删除
ALTER TABLE project
    ADD COLUMN created_by BIGINT DEFAULT NULL COMMENT '创建人(用户ID)' AFTER properties,
    ADD COLUMN modified_by BIGINT DEFAULT NULL COMMENT '最后修改人(用户ID)' AFTER created_by,
    ADD COLUMN delete_at DATETIME DEFAULT NULL COMMENT '删除时间(软删除标记)' AFTER modified_by;

UPDATE project SET created_by = owner_id WHERE created_by IS NULL;
UPDATE project SET modified_by = owner_id WHERE modified_by IS NULL;

UPDATE project
SET card_properties = JSON_ARRAY(
    JSON_OBJECT(
        'id', 'status', 'name', '状态', 'type', 'select',
        'options', JSON_ARRAY(
            JSON_OBJECT('id', 'todo', 'value', 'todo', 'color', 'blue'),
            JSON_OBJECT('id', 'in_progress', 'value', 'in_progress', 'color', 'orange'),
            JSON_OBJECT('id', 'done', 'value', 'done', 'color', 'green'))),
    JSON_OBJECT(
        'id', 'priority', 'name', '优先级', 'type', 'select',
        'options', JSON_ARRAY(
            JSON_OBJECT('id', 'low', 'value', 'low', 'color', 'gray'),
            JSON_OBJECT('id', 'medium', 'value', 'medium', 'color', 'blue'),
            JSON_OBJECT('id', 'high', 'value', 'high', 'color', 'orange'),
            JSON_OBJECT('id', 'urgent', 'value', 'urgent', 'color', 'red'))),
    JSON_OBJECT('id', 'assignee', 'name', '负责人', 'type', 'user'),
    JSON_OBJECT('id', 'dueDate', 'name', '截止时间', 'type', 'date'))
WHERE card_properties IS NULL;

-- 2. project_member 角色迁移 ---------------------------------------------
-- 新角色体系: viewer/commenter/editor/admin，负责人(owner_id)身份由 project 表承载，等同 admin
UPDATE project_member SET role = 'admin' WHERE role = 'owner';
UPDATE project_member SET role = 'editor' WHERE role = 'member';

-- 3. 统一块模型表 block --------------------------------------------------
CREATE TABLE block (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id  BIGINT       NOT NULL COMMENT '所属项目ID',
    parent_id   BIGINT       DEFAULT NULL COMMENT '父块ID(卡片内容块的父为卡片)',
    schema_ver  BIGINT       NOT NULL DEFAULT 1 COMMENT '块结构版本号',
    type        VARCHAR(20)  NOT NULL COMMENT '块类型: card/view/text/comment',
    title       VARCHAR(500) NOT NULL DEFAULT '' COMMENT '块标题',
    fields      JSON         DEFAULT NULL COMMENT '块动态字段(card: properties/contentOrder; text: content 等)',
    created_by  BIGINT       NOT NULL COMMENT '创建人(用户ID)',
    modified_by BIGINT       NOT NULL COMMENT '最后修改人(用户ID)',
    create_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    delete_at   DATETIME     DEFAULT NULL COMMENT '删除时间(软删除标记)',
    PRIMARY KEY (id),
    KEY idx_project_parent (project_id, parent_id),
    KEY idx_delete_at (delete_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '统一块模型表';

-- 4. task 数据迁移为 type=card 的 block ----------------------------------
-- 固定列 status/priority/assignee/due_date 写入 fields.properties，
-- description 保留为块标题之外的正文字段
INSERT INTO block (project_id, parent_id, schema_ver, type, title, fields,
                   created_by, modified_by, create_at, update_at, delete_at)
SELECT t.project_id, NULL, 1, 'card', t.title,
    JSON_OBJECT(
        'description', IFNULL(t.description, ''),
        'contentOrder', JSON_ARRAY(),
        'properties', JSON_OBJECT(
            'status', t.status,
            'priority', t.priority,
            'assignee', IFNULL(CAST(t.assignee_id AS JSON), CAST('null' AS JSON)),
            'dueDate', IFNULL(DATE_FORMAT(t.due_date, '%Y-%m-%d %H:%i:%s'), CAST('null' AS JSON)))),
    t.creator_id, t.creator_id, t.created_at, t.updated_at, NULL
FROM task t;

DROP TABLE task;
