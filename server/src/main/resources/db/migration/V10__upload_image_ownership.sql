-- =====================================================
-- V10: 图片上传归属记录
-- upload_image 记录每张 Markdown 插图文件的上传者，
-- 删除图片时校验归属，防止任意登录用户删除他人文件
-- =====================================================

CREATE TABLE upload_image (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    stored_name VARCHAR(64) NOT NULL COMMENT '存储文件名(UUID.ext)',
    created_by  BIGINT      NOT NULL COMMENT '上传人(用户ID)',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_image_name (stored_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '图片上传归属表';
