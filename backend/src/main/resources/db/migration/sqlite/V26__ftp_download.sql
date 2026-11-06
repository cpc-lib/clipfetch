CREATE TABLE ftp_download_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_uuid VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    protocol VARCHAR(20) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port INT NOT NULL,
    remote_path VARCHAR(4096) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_bytes BIGINT,
    downloaded_bytes BIGINT NOT NULL DEFAULT 0,
    expected_sha256 VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 1,
    execution_epoch BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(500),
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_ftp_task_uuid_deleted ON ftp_download_task (task_uuid, deleted);
CREATE INDEX idx_ftp_task_user_status ON ftp_download_task (user_id, status, deleted);
CREATE INDEX idx_ftp_task_user_created ON ftp_download_task (user_id, created_at, id);

CREATE TABLE ftp_task_credential (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    auth_mode VARCHAR(20) NOT NULL,
    encrypted_username TEXT,
    encrypted_password TEXT,
    username_nonce VARCHAR(64),
    password_nonce VARCHAR(64),
    key_id VARCHAR(64),
    cipher_version INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_ftp_credential_task_deleted ON ftp_task_credential (task_id, deleted);
CREATE INDEX idx_ftp_credential_user_task ON ftp_task_credential (user_id, task_id, deleted);

CREATE TABLE ftp_transfer_checkpoint (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    local_key VARCHAR(512) NOT NULL,
    remote_size BIGINT,
    remote_mtime VARCHAR(64),
    remote_unique_id VARCHAR(255),
    committed_bytes BIGINT NOT NULL DEFAULT 0,
    local_sha256 VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 1,
    last_verified_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_ftp_checkpoint_task_deleted ON ftp_transfer_checkpoint (task_id, deleted);
CREATE INDEX idx_ftp_checkpoint_user_task ON ftp_transfer_checkpoint (user_id, task_id, deleted);
