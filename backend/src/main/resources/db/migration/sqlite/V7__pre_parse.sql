-- ============================================================
-- V7 预解析库表（SQLite 3.x）
-- 用户预保存视频链接，解析成功后标记 parsed=1
-- ============================================================

CREATE TABLE IF NOT EXISTS pre_parse (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL,
    url        TEXT    NOT NULL,
    title      TEXT,
    parsed     INTEGER NOT NULL DEFAULT 0,
    deleted    INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL
);
-- 同用户下未删除记录 URL 唯一（部分索引）
CREATE UNIQUE INDEX IF NOT EXISTS uk_pre_parse_user_url_active
    ON pre_parse(user_id, url) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_pre_parse_user ON pre_parse(user_id, parsed);
