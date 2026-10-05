-- ============================================================
-- V3 临时子链接表（SQLite 3.x，Flyway 自动执行）
-- 用于保存集合页（如网易云歌手页）解析出的子链接（歌曲链接）。
-- ============================================================

CREATE TABLE IF NOT EXISTS temp (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    url        TEXT    NOT NULL,
    title      TEXT,
    source_url TEXT,
    created_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_temp_url ON temp(url);
