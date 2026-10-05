-- ============================================================
-- V2 第三方平台令牌会话（MySQL 5.7+ / 8.0，utf8mb4）
-- 每个 ClipFetch 用户在每个第三方平台仅保存一条会话：
-- dual_token_type=single 仅存 access_token；dual 时同时存 refresh_token。
-- 账号密码不落库：单 token 平台令牌过期后需用户重新连接。
-- ============================================================

CREATE TABLE IF NOT EXISTS `third_party_session`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT      NOT NULL COMMENT 'ClipFetch 用户 id',
    `platform`        VARCHAR(32) NOT NULL COMMENT '平台标签：rag_subtitle / ...',
    `dual_token_type` VARCHAR(16) NOT NULL COMMENT '令牌类型：single（仅 access）/ dual（access+refresh）',
    `access_token`    MEDIUMTEXT  NOT NULL COMMENT '第三方访问令牌',
    `refresh_token`   MEDIUMTEXT  DEFAULT NULL COMMENT '第三方刷新令牌（dual 类型时存在）',
    `created_at`      DATETIME(6) NOT NULL COMMENT '首次连接时间',
    `updated_at`      DATETIME(6) NOT NULL COMMENT '最近令牌更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tpsession_user_platform` (`user_id`, `platform`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='第三方平台令牌会话表';
