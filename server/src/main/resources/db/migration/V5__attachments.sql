-- =====================================================
-- V5: 任务附件表
-- 附件与任务(card块)关联，文件存 uploads 目录（UUID 文件名），
-- 记录元信息；删除附件为物理删除（文件 + 记录）。
-- =====================================================

CREATE TABLE attachment (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id       BIGINT       NOT NULL COMMENT '所属任务(card块)ID',
    original_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    stored_name   VARCHAR(255) NOT NULL COMMENT '存储文件名(UUID.ext)',
    content_type  VARCHAR(100) NOT NULL COMMENT 'MIME类型',
    size          BIGINT       NOT NULL COMMENT '文件大小(字节)',
    created_by    BIGINT       NOT NULL COMMENT '上传人(用户ID)',
    create_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '任务附件表';
