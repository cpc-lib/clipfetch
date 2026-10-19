-- Persist verified native metadata summaries only; separate from legacy magnet_history.
CREATE TABLE torrent_native_parse_result (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_uuid CHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 info_hash CHAR(40) NOT NULL,
 display_name VARCHAR(1024) NOT NULL,
 total_bytes BIGINT NOT NULL,
 files_json MEDIUMTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 deleted BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_native_parse_owner_job (user_id, job_uuid, deleted),
 KEY idx_native_parse_owner (user_id, deleted, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
