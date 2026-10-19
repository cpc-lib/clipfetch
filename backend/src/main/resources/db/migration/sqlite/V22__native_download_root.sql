-- ============================================================
-- V22 用户自选 Native 下载根目录（SQLite 3.x）
-- 只保存用户在服务端批准目录范围内挑选的目录；删除一律 deleted = id
-- ============================================================

CREATE TABLE IF NOT EXISTS native_download_root (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL,
    biz_key    TEXT    NOT NULL DEFAULT 'default',
    root_path  TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0
);
-- 每个用户同一 biz_key 只保留一条未删除记录（deleted = id 保证多条删除不冲突）
CREATE UNIQUE INDEX IF NOT EXISTS uk_native_download_root_active
    ON native_download_root(user_id, biz_key, deleted);
CREATE INDEX IF NOT EXISTS idx_native_download_root_user
    ON native_download_root(user_id, deleted, updated_at);
