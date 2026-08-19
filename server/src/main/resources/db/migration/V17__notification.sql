-- =====================================================
-- V17: 站内通知
-- 评论中 @项目成员后，被提及成员收到一条 mention 通知；
-- snippet 冗余存储评论摘要，避免展示时联查评论块。
-- =====================================================

CREATE TABLE notification (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT       NOT NULL COMMENT '接收人(用户ID)',
    type       VARCHAR(32)  NOT NULL COMMENT '通知类型: mention',
    project_id BIGINT       NOT NULL COMMENT '关联项目ID',
    task_id    BIGINT       NOT NULL COMMENT '关联任务(卡片块)ID',
    comment_id BIGINT       NOT NULL COMMENT '关联评论块ID',
    actor_id   BIGINT       NOT NULL COMMENT '操作人(用户ID)',
    snippet    VARCHAR(255) DEFAULT NULL COMMENT '评论摘要(截断存储)',
    is_read    TINYINT      NOT NULL DEFAULT 0 COMMENT '已读状态: 0 未读, 1 已读',
    create_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_read (user_id, is_read, create_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '站内通知表';
