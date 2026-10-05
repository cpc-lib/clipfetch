-- ============================================================
-- V2 第三方平台令牌会话（SQLite 3.x，Flyway 自动执行）
-- 每个 ClipFetch 用户在每个第三方平台仅保存一条会话：
-- dual_token_type=single 仅存 access_token；dual 时同时存 refresh_token。
-- 账号密码不落库：单 token 平台令牌过期后需用户重新连接。
-- ============================================================

CREATE TABLE IF NOT EXISTS third_party_session (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER      NOT NULL,
    platform        VARCHAR(32)  NOT NULL,
    dual_token_type VARCHAR(16)  NOT NULL,
    access_token    TEXT         NOT NULL,
    refresh_token   TEXT,
    created_at      TEXT         NOT NULL,
    updated_at      TEXT         NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tpsession_user_platform
    ON third_party_session(user_id, platform);
