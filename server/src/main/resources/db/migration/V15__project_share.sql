-- =====================================================
-- V15: 项目分享链接
-- 公开项目不再对全员可见，改为"仅持分享链接的登录用户可只读访问"：
-- share_id 为 UUID 分享凭证（NULL=未开启分享），关闭分享即清空该字段使旧链接失效。
-- =====================================================

ALTER TABLE project
    ADD COLUMN share_id VARCHAR(36) DEFAULT NULL COMMENT '分享链接标识(UUID,NULL=未开启)' AFTER type;
