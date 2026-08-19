-- =====================================================
-- V18: 项目新增日历订阅凭证字段
-- calendar_token 为 ICS 订阅地址的访问凭证（NULL=未开启订阅）
-- =====================================================

ALTER TABLE project ADD COLUMN calendar_token VARCHAR(64) DEFAULT NULL COMMENT '日历订阅凭证(UUID，NULL=未开启订阅)';
CREATE UNIQUE INDEX uk_project_calendar_token ON project (calendar_token);
