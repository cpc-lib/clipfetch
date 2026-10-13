CREATE TABLE IF NOT EXISTS magnet_download_task (
    task_uuid TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    parse_id INTEGER NOT NULL,
    info_hash TEXT NOT NULL UNIQUE,
    save_path TEXT NOT NULL,
    selected_files_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_magnet_download_user ON magnet_download_task(user_id, created_at);
