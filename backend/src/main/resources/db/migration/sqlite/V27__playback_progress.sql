CREATE TABLE playback_progress (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,
    source_key VARCHAR(64) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    platform VARCHAR(64),
    title VARCHAR(512),
    format_id VARCHAR(256),
    mode VARCHAR(16),
    position_seconds INT NOT NULL DEFAULT 0,
    duration_seconds INT,
    completed INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_playback_progress_user_key ON playback_progress (user_id, source_key, deleted);
CREATE INDEX idx_playback_progress_user ON playback_progress (user_id, updated_at);
