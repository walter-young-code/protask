-- =====================================================
-- V21: WBS 层级与 FS 依赖（仅添加「WBS 编码」属性的项目启用）
-- block 增加 parent_task_id / sort_order；新增 task_dependency 表
-- 未添加 WBS 编码属性的项目不调用相关写路径，行为与升级前一致。
-- =====================================================

-- 任务父子关系（仅 WBS 项目生效；非 WBS 项目恒为 NULL）
ALTER TABLE block ADD COLUMN parent_task_id BIGINT DEFAULT NULL COMMENT '父任务ID(仅WBS项目生效)' AFTER parent_id;
ALTER TABLE block ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '同级排序序号(WBS项目树序)' AFTER parent_task_id;
CREATE INDEX idx_block_parent_task ON block (parent_task_id);

-- 任务依赖关系（FS 完成-开始；仅 WBS 项目生效）
CREATE TABLE task_dependency (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id     BIGINT      NOT NULL COMMENT '所属项目ID',
    predecessor_id BIGINT      NOT NULL COMMENT '前置任务ID',
    successor_id   BIGINT      NOT NULL COMMENT '后置任务ID',
    type           VARCHAR(8)  NOT NULL DEFAULT 'FS' COMMENT '依赖类型: FS/SS/FF/SF',
    `lag`          INT         NOT NULL DEFAULT 0 COMMENT '延迟天数',
    create_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dep_pair (predecessor_id, successor_id),
    KEY idx_dep_project (project_id),
    KEY idx_dep_successor (successor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '任务依赖关系表';