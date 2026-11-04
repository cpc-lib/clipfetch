CREATE TABLE magnet_v2_resource (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    resource_uuid TEXT NOT NULL,
    canonical_key TEXT NOT NULL,
    btih BLOB NULL,
    btmh BLOB NULL,
    version_type TEXT NOT NULL,
    name TEXT NOT NULL,
    piece_length INTEGER NOT NULL,
    total_bytes INTEGER NOT NULL,
    file_count INTEGER NOT NULL,
    private_flag INTEGER NOT NULL DEFAULT 0,
    metadata_key TEXT NULL,
    metadata_status TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_magnet_v2_resource_uuid UNIQUE (resource_uuid),
    CONSTRAINT uq_magnet_v2_resource_canonical UNIQUE (canonical_key),
    CONSTRAINT ck_magnet_v2_resource_btih CHECK (btih IS NULL OR length(btih) = 20),
    CONSTRAINT ck_magnet_v2_resource_btmh CHECK (btmh IS NULL OR length(btmh) = 32)
);

CREATE TABLE magnet_v2_resource_hash_alias (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    resource_id INTEGER NOT NULL,
    hash_type TEXT NOT NULL,
    hash_bytes BLOB NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_magnet_v2_hash_alias UNIQUE (hash_type, hash_bytes),
    CONSTRAINT ck_magnet_v2_hash_alias_type_length CHECK (
        (hash_type = 'BTIH' AND length(hash_bytes) = 20)
        OR (hash_type = 'BTMH' AND length(hash_bytes) = 32)
    ),
    CONSTRAINT fk_magnet_v2_hash_alias_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
);

CREATE INDEX idx_magnet_v2_hash_alias_resource
    ON magnet_v2_resource_hash_alias (resource_id);

CREATE TABLE magnet_v2_resource_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    resource_id INTEGER NOT NULL,
    file_index INTEGER NOT NULL,
    relative_path TEXT NOT NULL,
    declared_bytes INTEGER NOT NULL,
    pieces_root BLOB NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_magnet_v2_resource_file UNIQUE (resource_id, file_index),
    CONSTRAINT ck_magnet_v2_resource_file_root CHECK (pieces_root IS NULL OR length(pieces_root) = 32),
    CONSTRAINT fk_magnet_v2_resource_file_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
);

CREATE TABLE magnet_v2_parse_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parse_uuid TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    input_type TEXT NOT NULL,
    input_hash BLOB NOT NULL,
    btih BLOB NULL,
    btmh BLOB NULL,
    trackers_json TEXT NOT NULL,
    input_storage_key TEXT NULL,
    resource_id INTEGER NULL,
    status TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TEXT NULL,
    error_code TEXT NULL,
    error_message TEXT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_magnet_v2_parse_job_logical UNIQUE (parse_uuid, deleted),
    CONSTRAINT ck_magnet_v2_parse_input_hash CHECK (length(input_hash) = 32),
    CONSTRAINT ck_magnet_v2_parse_btih CHECK (btih IS NULL OR length(btih) = 20),
    CONSTRAINT ck_magnet_v2_parse_btmh CHECK (btmh IS NULL OR length(btmh) = 32),
    CONSTRAINT fk_magnet_v2_parse_job_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
);

CREATE INDEX idx_magnet_v2_parse_user_status
    ON magnet_v2_parse_job (user_id, status, deleted);
CREATE INDEX idx_magnet_v2_parse_due
    ON magnet_v2_parse_job (status, next_retry_at, deleted);
CREATE INDEX idx_magnet_v2_parse_resource
    ON magnet_v2_parse_job (resource_id);
CREATE INDEX idx_magnet_v2_parse_owner
    ON magnet_v2_parse_job (id, user_id, deleted);

CREATE TABLE magnet_v2_download_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_uuid TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    resource_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    selected_bytes INTEGER NOT NULL DEFAULT 0,
    verified_bytes INTEGER NOT NULL DEFAULT 0,
    priority INTEGER NOT NULL DEFAULT 0,
    speed_limit INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TEXT NULL,
    error_code TEXT NULL,
    error_message TEXT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_magnet_v2_download_task_logical UNIQUE (task_uuid, deleted),
    CONSTRAINT fk_magnet_v2_download_task_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
);

CREATE INDEX idx_magnet_v2_task_user_status
    ON magnet_v2_download_task (user_id, status, deleted);
CREATE INDEX idx_magnet_v2_task_due
    ON magnet_v2_download_task (status, next_retry_at, deleted);
CREATE INDEX idx_magnet_v2_task_resource
    ON magnet_v2_download_task (resource_id, status, deleted);
CREATE INDEX idx_magnet_v2_task_cas
    ON magnet_v2_download_task (id, user_id, revision, deleted);

CREATE TABLE magnet_v2_download_task_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    task_id INTEGER NOT NULL,
    file_index INTEGER NOT NULL,
    selected INTEGER NOT NULL DEFAULT 0,
    declared_bytes INTEGER NOT NULL,
    verified_bytes INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_magnet_v2_task_file_logical UNIQUE (task_id, file_index, deleted),
    CONSTRAINT fk_magnet_v2_task_file_task FOREIGN KEY (task_id)
        REFERENCES magnet_v2_download_task (id)
);

CREATE INDEX idx_magnet_v2_task_file_owner
    ON magnet_v2_download_task_file (id, user_id, deleted);
CREATE INDEX idx_magnet_v2_task_file_user_task
    ON magnet_v2_download_task_file (user_id, task_id, deleted);

CREATE TABLE magnet_v2_engine_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    resource_id INTEGER NOT NULL,
    engine_session_id TEXT NOT NULL,
    worker_id TEXT NULL,
    state TEXT NOT NULL,
    active_task_id INTEGER NULL,
    owner_user_id INTEGER NULL,
    owner_epoch INTEGER NOT NULL DEFAULT 1,
    resume_version INTEGER NOT NULL DEFAULT 0,
    resume_key TEXT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_magnet_v2_session_resource UNIQUE (resource_id),
    CONSTRAINT uq_magnet_v2_session_engine_id UNIQUE (engine_session_id),
    CONSTRAINT fk_magnet_v2_session_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id),
    CONSTRAINT fk_magnet_v2_session_active_task FOREIGN KEY (active_task_id)
        REFERENCES magnet_v2_download_task (id)
);

CREATE TABLE magnet_v2_task_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    task_id INTEGER NOT NULL,
    sequence INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_magnet_v2_task_event_sequence UNIQUE (task_id, sequence),
    CONSTRAINT fk_magnet_v2_task_event_task FOREIGN KEY (task_id)
        REFERENCES magnet_v2_download_task (id)
);

CREATE INDEX idx_magnet_v2_task_event_user_task
    ON magnet_v2_task_event (user_id, task_id);
