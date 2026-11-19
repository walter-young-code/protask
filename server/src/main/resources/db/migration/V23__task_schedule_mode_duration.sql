-- =====================================================
-- V23: 任务工期与计划模式（仅添加「WBS 编码」属性的项目生效）
-- schedule_mode: manual=手动计划（默认，存量行为不变）, auto=自动计划（前置驱动排程）
-- duration_days: 显式工期（自然日含首日），NULL=按起止日期推导
-- start_constraint: 自动计划「不得早于」开始约束，NULL=纯前置驱动
-- =====================================================
ALTER TABLE block
    ADD COLUMN schedule_mode VARCHAR(8) NOT NULL DEFAULT 'manual' COMMENT '计划模式: manual手动/auto自动(仅WBS项目生效)' AFTER milestone,
    ADD COLUMN duration_days INT NULL COMMENT '显式工期(自然日含首日); NULL=按起止日期推导' AFTER schedule_mode,
    ADD COLUMN start_constraint DATE NULL COMMENT '自动计划开始约束(不得早于); NULL=纯前置驱动' AFTER duration_days;