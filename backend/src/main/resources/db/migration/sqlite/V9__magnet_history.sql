-- ============================================================
-- V9 磁力解析历史表（SQLite 3.x）
-- 保存磁力链接解析出的元数据与文件清单；下载任务状态由 qBittorrent 管理
-- ============================================================

CREATE TABLE IF NOT EXISTS magnet_history (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL,
    info_hash  TEXT    NOT NULL,
    magnet_uri TEXT    NOT NULL,
    name       TEXT,
    total_size INTEGER,
    files_json TEXT,
    status     TEXT    NOT NULL,
    error      TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);
-- 同用户下未删除记录 Info Hash 唯一（部分索引）
CREATE UNIQUE INDEX IF NOT EXISTS uk_magnet_history_user_hash_active
    ON magnet_history(user_id, info_hash) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_magnet_history_user ON magnet_history(user_id, created_at);
