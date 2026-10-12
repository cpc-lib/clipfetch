-- ============================================================
-- V8 temp 表新增 user_id 字段（SQLite 3.x）
-- 修复缺陷：temp 表无 user_id，导致不同用户的文件库数据互相可见
-- ============================================================

ALTER TABLE temp ADD COLUMN user_id INTEGER NOT NULL DEFAULT 0;

-- 历史数据 user_id=0，新数据按用户隔离
CREATE INDEX IF NOT EXISTS idx_temp_user ON temp(user_id, downloaded);

-- url 唯一索引改为 (user_id, url) 组合唯一（SQLite 无 ALTER INDEX，重建索引）
-- 先删旧的 url 单列唯一索引
DROP INDEX IF EXISTS uk_temp_url;
-- 再建组合唯一索引（含已删除记录，重新导入时按 user+url 去重）
CREATE UNIQUE INDEX IF NOT EXISTS uk_temp_user_url ON temp(user_id, url);
