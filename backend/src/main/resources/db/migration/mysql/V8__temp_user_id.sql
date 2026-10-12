-- ============================================================
-- V8 temp 表新增 user_id 字段（MySQL 5.7+ / 8.0）
-- 修复缺陷：temp 表无 user_id，导致不同用户的文件库数据互相可见
-- ============================================================

ALTER TABLE `temp`
    ADD COLUMN `user_id` BIGINT NOT NULL DEFAULT 0 COMMENT '所属用户 ID';

-- 历史数据 user_id=0，新数据按用户隔离
ALTER TABLE `temp`
    ADD KEY `idx_temp_user` (`user_id`, `downloaded`);

-- url 唯一索引改为 (user_id, url) 组合唯一
ALTER TABLE `temp`
    DROP KEY `uk_temp_url`;
ALTER TABLE `temp`
    ADD UNIQUE KEY `uk_temp_user_url` (`user_id`, `url`(255));
