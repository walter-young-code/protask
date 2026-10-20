-- =====================================================
-- V19: 附件存储路径唯一约束
-- 附件改为按 {projectId}/{taskId}/ 分目录存储，stored_name 保存相对路径；
-- 同一任务内路径唯一，防止并发/异常写入产生重复记录（历史 UUID 扁平命名天然满足）。
-- =====================================================

ALTER TABLE attachment
    ADD UNIQUE KEY uk_task_stored_name (task_id, stored_name);
