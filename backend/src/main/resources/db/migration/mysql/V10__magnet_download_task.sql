-- 一份 infoHash 在共享 qBittorrent 中只能对应一个受控任务
CREATE TABLE IF NOT EXISTS magnet_download_task (
    task_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    parse_id BIGINT NOT NULL,
    info_hash VARCHAR(40) NOT NULL,
    save_path VARCHAR(1024) NOT NULL,
    selected_files_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_magnet_download_hash (info_hash),
    KEY idx_magnet_download_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
