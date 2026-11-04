CREATE TABLE magnet_v2_resource (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_uuid VARCHAR(36) NOT NULL,
    canonical_key VARCHAR(255) NOT NULL,
    btih VARBINARY(20) NULL,
    btmh VARBINARY(32) NULL,
    version_type VARCHAR(16) NOT NULL,
    name VARCHAR(1024) NOT NULL,
    piece_length BIGINT NOT NULL,
    total_bytes BIGINT NOT NULL,
    file_count INT NOT NULL,
    private_flag BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_key VARCHAR(512) NULL,
    metadata_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_resource_uuid UNIQUE (resource_uuid),
    CONSTRAINT uq_magnet_v2_resource_canonical UNIQUE (canonical_key),
    CONSTRAINT ck_magnet_v2_resource_btih CHECK (btih IS NULL OR OCTET_LENGTH(btih) = 20),
    CONSTRAINT ck_magnet_v2_resource_btmh CHECK (btmh IS NULL OR OCTET_LENGTH(btmh) = 32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE magnet_v2_resource_hash_alias (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    hash_type VARCHAR(16) NOT NULL,
    hash_bytes VARBINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_hash_alias UNIQUE (hash_type, hash_bytes),
    CONSTRAINT ck_magnet_v2_hash_alias_type_length CHECK (
        (hash_type = 'BTIH' AND OCTET_LENGTH(hash_bytes) = 20)
        OR (hash_type = 'BTMH' AND OCTET_LENGTH(hash_bytes) = 32)
    ),
    CONSTRAINT fk_magnet_v2_hash_alias_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_magnet_v2_hash_alias_resource
    ON magnet_v2_resource_hash_alias (resource_id);

CREATE TABLE magnet_v2_resource_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    file_index INT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    declared_bytes BIGINT NOT NULL,
    pieces_root VARBINARY(32) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_resource_file UNIQUE (resource_id, file_index),
    CONSTRAINT ck_magnet_v2_resource_file_root CHECK (
        pieces_root IS NULL OR OCTET_LENGTH(pieces_root) = 32
    ),
    CONSTRAINT fk_magnet_v2_resource_file_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE magnet_v2_parse_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parse_uuid VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    input_type VARCHAR(16) NOT NULL,
    input_hash VARBINARY(32) NOT NULL,
    btih VARBINARY(20) NULL,
    btmh VARBINARY(32) NULL,
    trackers_json LONGTEXT NOT NULL,
    input_storage_key VARCHAR(512) NULL,
    resource_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_parse_job_logical UNIQUE (parse_uuid, deleted),
    CONSTRAINT ck_magnet_v2_parse_input_hash CHECK (OCTET_LENGTH(input_hash) = 32),
    CONSTRAINT ck_magnet_v2_parse_btih CHECK (btih IS NULL OR OCTET_LENGTH(btih) = 20),
    CONSTRAINT ck_magnet_v2_parse_btmh CHECK (btmh IS NULL OR OCTET_LENGTH(btmh) = 32),
    CONSTRAINT fk_magnet_v2_parse_job_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_magnet_v2_parse_user_status
    ON magnet_v2_parse_job (user_id, status, deleted);
CREATE INDEX idx_magnet_v2_parse_due
    ON magnet_v2_parse_job (status, next_retry_at, deleted);
CREATE INDEX idx_magnet_v2_parse_resource
    ON magnet_v2_parse_job (resource_id);
CREATE INDEX idx_magnet_v2_parse_owner
    ON magnet_v2_parse_job (id, user_id, deleted);

CREATE TABLE magnet_v2_download_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_uuid VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    selected_bytes BIGINT NOT NULL DEFAULT 0,
    verified_bytes BIGINT NOT NULL DEFAULT 0,
    priority INT NOT NULL DEFAULT 0,
    speed_limit BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_download_task_logical UNIQUE (task_uuid, deleted),
    CONSTRAINT fk_magnet_v2_download_task_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_magnet_v2_task_user_status
    ON magnet_v2_download_task (user_id, status, deleted);
CREATE INDEX idx_magnet_v2_task_due
    ON magnet_v2_download_task (status, next_retry_at, deleted);
CREATE INDEX idx_magnet_v2_task_resource
    ON magnet_v2_download_task (resource_id, status, deleted);
CREATE INDEX idx_magnet_v2_task_cas
    ON magnet_v2_download_task (id, user_id, revision, deleted);

CREATE TABLE magnet_v2_download_task_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    file_index INT NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    declared_bytes BIGINT NOT NULL,
    verified_bytes BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_task_file_logical UNIQUE (task_id, file_index, deleted),
    CONSTRAINT fk_magnet_v2_task_file_task FOREIGN KEY (task_id)
        REFERENCES magnet_v2_download_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_magnet_v2_task_file_owner
    ON magnet_v2_download_task_file (id, user_id, deleted);
CREATE INDEX idx_magnet_v2_task_file_user_task
    ON magnet_v2_download_task_file (user_id, task_id, deleted);

CREATE TABLE magnet_v2_engine_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    engine_session_id VARCHAR(64) NOT NULL,
    worker_id VARCHAR(128) NULL,
    state VARCHAR(32) NOT NULL,
    active_task_id BIGINT NULL,
    owner_user_id BIGINT NULL,
    owner_epoch BIGINT NOT NULL DEFAULT 1,
    resume_version BIGINT NOT NULL DEFAULT 0,
    resume_key VARCHAR(512) NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_session_resource UNIQUE (resource_id),
    CONSTRAINT uq_magnet_v2_session_engine_id UNIQUE (engine_session_id),
    CONSTRAINT fk_magnet_v2_session_resource FOREIGN KEY (resource_id)
        REFERENCES magnet_v2_resource (id),
    CONSTRAINT fk_magnet_v2_session_active_task FOREIGN KEY (active_task_id)
        REFERENCES magnet_v2_download_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE magnet_v2_task_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_magnet_v2_task_event_sequence UNIQUE (task_id, sequence),
    CONSTRAINT fk_magnet_v2_task_event_task FOREIGN KEY (task_id)
        REFERENCES magnet_v2_download_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_magnet_v2_task_event_user_task
    ON magnet_v2_task_event (user_id, task_id);
