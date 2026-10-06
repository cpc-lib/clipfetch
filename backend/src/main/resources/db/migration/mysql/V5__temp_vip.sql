-- ============================================================
-- V5 temp 表新增 vip 字段（MySQL）
-- QQ 音乐歌单解析时标记歌曲是否 VIP 专属：0=普通 1=VIP（pay.pay_play=1）
-- ============================================================
ALTER TABLE `temp` ADD COLUMN `vip` TINYINT NOT NULL DEFAULT 0;
