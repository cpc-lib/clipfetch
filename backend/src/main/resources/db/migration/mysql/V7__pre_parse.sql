-- ============================================================
-- V7 预解析库表（MySQL 5.7+ / 8.0，utf8mb4）
-- 用户预保存视频链接，解析成功后标记 parsed=1
-- ============================================================

CREATE TABLE IF NOT EXISTS `pre_parse`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT       NOT NULL COMMENT '所属用户 ID',
    `url`        TEXT         NOT NULL COMMENT '视频链接',
    `title`      VARCHAR(512) DEFAULT NULL COMMENT '文件名称（可空，不填自动生成）',
    `parsed`     TINYINT      NOT NULL DEFAULT 0 COMMENT '解析状态：0=未解析 1=已解析',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 非0=已删除（值为记录 id）',
    `created_at` DATETIME(6)  NOT NULL COMMENT '保存时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pre_parse_user_url_deleted` (`user_id`, `url`(512), `deleted`),
    KEY `idx_pre_parse_user` (`user_id`, `parsed`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='预解析库';
