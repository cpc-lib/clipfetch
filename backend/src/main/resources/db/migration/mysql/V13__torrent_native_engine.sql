-- Native tasks are independent from legacy qBittorrent's global info_hash uniqueness.
CREATE TABLE torrent_native_task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_uuid CHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 parse_id BIGINT NOT NULL,
 info_hash VARCHAR(64) NOT NULL,
 metadata_id BIGINT NULL,
 status VARCHAR(32) NOT NULL,
 selected_files_json LONGTEXT NOT NULL,
 selected_total_bytes BIGINT NOT NULL DEFAULT 0,
 verified_selected_bytes BIGINT NOT NULL DEFAULT 0,
 downloaded_wire_bytes BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0,
 state_path VARCHAR(1024) NOT NULL,
 last_error VARCHAR(1024) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 deleted BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_torrent_native_uuid (task_uuid),
 KEY idx_torrent_native_owner (user_id, deleted, created_at),
 KEY idx_torrent_native_hash (info_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE torrent_native_file (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_uuid CHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 file_index INT NOT NULL,
 relative_path VARCHAR(1024) NOT NULL,
 total_bytes BIGINT NOT NULL,
 verified_bytes BIGINT NOT NULL DEFAULT 0,
 is_selected TINYINT(1) NOT NULL,
 UNIQUE KEY uk_torrent_native_task_file (task_uuid, file_index),
 KEY idx_torrent_native_file_owner (user_id, task_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE torrent_native_metadata (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 info_hash VARCHAR(64) NOT NULL,
 torrent_bencode LONGBLOB NOT NULL,
 metainfo_sha256 CHAR(64) NOT NULL,
 piece_length INT NOT NULL,
 piece_count BIGINT NOT NULL,
 total_bytes BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_torrent_native_metadata_hash (info_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
