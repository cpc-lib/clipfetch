-- Persist verified native metadata summaries only; in-flight metadata jobs remain memory-only.
CREATE TABLE torrent_native_parse_result (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 job_uuid TEXT NOT NULL,
 user_id INTEGER NOT NULL,
 info_hash TEXT NOT NULL,
 display_name TEXT NOT NULL,
 total_bytes INTEGER NOT NULL,
 files_json TEXT NOT NULL,
 created_at TEXT NOT NULL,
 deleted INTEGER NOT NULL DEFAULT 0,
 UNIQUE(user_id, job_uuid, deleted)
);
CREATE INDEX idx_torrent_native_parse_owner ON torrent_native_parse_result(user_id, deleted, created_at);
