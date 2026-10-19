-- Separate user/task UUIDs: info_hash is deliberately not globally unique.
CREATE TABLE torrent_native_task (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 task_uuid TEXT NOT NULL UNIQUE,
 user_id INTEGER NOT NULL,
 parse_id INTEGER NOT NULL,
 info_hash TEXT NOT NULL,
 metadata_id INTEGER,
 status TEXT NOT NULL,
 selected_files_json TEXT NOT NULL,
 selected_total_bytes INTEGER NOT NULL DEFAULT 0,
 verified_selected_bytes INTEGER NOT NULL DEFAULT 0,
 downloaded_wire_bytes INTEGER NOT NULL DEFAULT 0,
 revision INTEGER NOT NULL DEFAULT 0,
 state_path TEXT NOT NULL,
 last_error TEXT,
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL,
 completed_at TEXT,
 deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_torrent_native_owner ON torrent_native_task(user_id, deleted, created_at);
CREATE INDEX idx_torrent_native_hash ON torrent_native_task(info_hash);

CREATE TABLE torrent_native_file (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 task_uuid TEXT NOT NULL,
 user_id INTEGER NOT NULL,
 file_index INTEGER NOT NULL,
 relative_path TEXT NOT NULL,
 total_bytes INTEGER NOT NULL,
 verified_bytes INTEGER NOT NULL DEFAULT 0,
 is_selected INTEGER NOT NULL,
 UNIQUE(task_uuid, file_index)
);
CREATE INDEX idx_torrent_native_file_owner ON torrent_native_file(user_id, task_uuid);

CREATE TABLE torrent_native_metadata (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 info_hash TEXT NOT NULL UNIQUE,
 torrent_bencode BLOB NOT NULL,
 metainfo_sha256 TEXT NOT NULL,
 piece_length INTEGER NOT NULL,
 piece_count INTEGER NOT NULL,
 total_bytes INTEGER NOT NULL,
 created_at TEXT NOT NULL
);
