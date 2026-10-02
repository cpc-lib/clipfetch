-- ============================================================
-- V1 初始建表（SQLite 3.x，由 SqliteSchemaInitializer 首次启动时执行）
-- 表：user / refresh_token / user_cookie
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64),
    vip           INTEGER      NOT NULL DEFAULT 0,
    created_at    TEXT         NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_email ON user(email);

-- Refresh Token 表
CREATE TABLE IF NOT EXISTS refresh_token (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER     NOT NULL,
    token      VARCHAR(64) NOT NULL,
    expires_at TEXT        NOT NULL,
    revoked    INTEGER     NOT NULL DEFAULT 0,
    created_at TEXT        NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_refresh_token ON refresh_token(token);
CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_token(user_id);

-- 用户平台 Cookies 表
CREATE TABLE IF NOT EXISTS user_cookie (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          INTEGER     NOT NULL,
    platform         VARCHAR(16) NOT NULL,
    content          TEXT        NOT NULL,
    valid            INTEGER     NOT NULL DEFAULT 1,
    status_message   VARCHAR(255),
    last_verified_at TEXT,
    last_used_at     TEXT,
    created_at       TEXT        NOT NULL,
    updated_at       TEXT        NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_cookie_user_platform ON user_cookie(user_id, platform);
