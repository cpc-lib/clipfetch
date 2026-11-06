CREATE TABLE IF NOT EXISTS ed2k_resource (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    md4_hash VARCHAR(32) NOT NULL,
    file_size BIGINT NOT NULL,
    internal_name VARCHAR(255) NOT NULL,
    engine_state VARCHAR(24) NOT NULL,
    engine_managed INTEGER NOT NULL DEFAULT 1,
    engine_ref VARCHAR(64),
    completed_path VARCHAR(512),
    completed_verified_at DATETIME,
    observed_bytes BIGINT NOT NULL DEFAULT 0,
    speed_bps BIGINT NOT NULL DEFAULT 0,
    sources_total INT NOT NULL DEFAULT 0,
    sources_transferring INT NOT NULL DEFAULT 0,
    last_snapshot_at DATETIME,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    revision BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ed2k_resource_identity
    ON ed2k_resource (md4_hash, file_size);

CREATE TABLE IF NOT EXISTS ed2k_download_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_uuid VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    requested_name VARCHAR(255) NOT NULL,
    desired_state VARCHAR(16) NOT NULL,
    task_state VARCHAR(24) NOT NULL,
    progress_bytes BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 1,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ed2k_task_uuid_deleted
    ON ed2k_download_task (task_uuid, deleted);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ed2k_task_user_resource_deleted
    ON ed2k_download_task (user_id, resource_id, deleted);

CREATE INDEX IF NOT EXISTS idx_ed2k_task_user_state
    ON ed2k_download_task (user_id, desired_state, deleted);

CREATE INDEX IF NOT EXISTS idx_ed2k_task_resource_state
    ON ed2k_download_task (resource_id, desired_state, deleted);

CREATE TABLE IF NOT EXISTS ed2k_engine_action (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action_uuid VARCHAR(36) NOT NULL,
    resource_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    target_revision BIGINT NOT NULL,
    action_state VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME,
    last_error_code VARCHAR(64),
    last_error VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ed2k_action_uuid
    ON ed2k_engine_action (action_uuid);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ed2k_action_dedup
    ON ed2k_engine_action (resource_id, target_revision, action);

CREATE INDEX IF NOT EXISTS idx_ed2k_action_state_retry
    ON ed2k_engine_action (action_state, next_retry_at);
