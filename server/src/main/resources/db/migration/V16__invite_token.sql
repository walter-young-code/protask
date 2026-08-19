-- =====================================================
-- V16: 注册邀请链接
-- 关闭公开注册后，仅持有效邀请链接的用户可注册：
-- token 为一次性凭证（status=1 即作废），过期时间默认 7 天。
-- =====================================================

CREATE TABLE invite_token (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    token      VARCHAR(64)  NOT NULL COMMENT '邀请凭证(随机串)',
    invited_by BIGINT       NOT NULL COMMENT '邀请人(用户ID)',
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0 未使用, 1 已使用',
    expires_at DATETIME     NOT NULL COMMENT '过期时间',
    used_at    DATETIME     DEFAULT NULL COMMENT '使用时间',
    used_by    BIGINT       DEFAULT NULL COMMENT '注册用户ID',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token (token),
    KEY idx_invited_by (invited_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '注册邀请表';
