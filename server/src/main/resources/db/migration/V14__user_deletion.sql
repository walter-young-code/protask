-- =====================================================
-- V14: 用户注销（软删除标记）
-- 注销 = 置 deleted_at + status=0 + 密码版本号 +1（旧 token 立即失效），
-- 用户记录保留以便历史任务/评论/附件等展示创建人信息。
-- =====================================================

ALTER TABLE sys_user
    ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '注销时间(NULL=未注销)' AFTER updated_at;
