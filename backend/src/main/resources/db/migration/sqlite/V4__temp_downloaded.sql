-- ============================================================
-- V4 temp 表增强（SQLite 3.x）
-- 1. url 加唯一约束（重复子链接跳过保存）
-- 2. 新增 downloaded 字段标记是否已下载
-- ============================================================

-- SQLite 无法直接给已有表加唯一约束，重建表
CREATE TABLE IF NOT EXISTS temp_new (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    url        TEXT    NOT NULL,
    title      TEXT,
    source_url TEXT,
    downloaded INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL
);
INSERT OR IGNORE INTO temp_new (id, url, title, source_url, created_at)
    SELECT id, url, title, source_url, created_at FROM temp WHERE url IS NOT NULL AND url != '';
DROP TABLE temp;
ALTER TABLE temp_new RENAME TO temp;
CREATE UNIQUE INDEX IF NOT EXISTS uk_temp_url ON temp(url);
CREATE INDEX IF NOT EXISTS idx_temp_downloaded ON temp(downloaded);
