-- ============================================================
-- V4 temp 表增强（MySQL 5.7+ / 8.0）
-- 1. url 改为 NOT NULL
-- 2. 新增 downloaded 字段标记是否已下载
-- 3. url 加唯一约束
-- ============================================================

DELETE FROM `temp` WHERE `url` IS NULL OR `url` = '';

ALTER TABLE `temp`
    MODIFY COLUMN `url` TEXT NOT NULL COMMENT '子链接（如歌曲链接）',
    ADD COLUMN `downloaded` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已下载：0=未下载 1=已下载';

ALTER TABLE `temp`
    ADD UNIQUE KEY `uk_temp_url` (`url`(255));

ALTER TABLE `temp`
    ADD KEY `idx_temp_downloaded` (`downloaded`);
