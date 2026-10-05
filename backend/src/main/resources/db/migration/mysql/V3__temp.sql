-- ============================================================
-- V3 临时子链接表（MySQL 5.7+ / 8.0，utf8mb4）
-- 用于保存集合页（如网易云歌手页）解析出的子链接（歌曲链接）。
-- ============================================================

CREATE TABLE IF NOT EXISTS `temp`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `url`        TEXT         NOT NULL COMMENT '子链接（如歌曲链接）',
    `title`      VARCHAR(512) DEFAULT NULL COMMENT '子链接标题（如歌曲名）',
    `source_url` TEXT         DEFAULT NULL COMMENT '来源集合链接（如歌手页）',
    `created_at` DATETIME(6)  NOT NULL COMMENT '保存时间',
    PRIMARY KEY (`id`),
    KEY `idx_temp_url` (`url`(255))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='临时子链接表';
