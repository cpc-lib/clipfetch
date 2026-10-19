-- Native durable retry intent only. An intent NEVER automatically starts a network Worker.
CREATE TABLE torrent_native_retry_plan (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 task_uuid TEXT NOT NULL,
 user_id INTEGER NOT NULL,
 expected_revision INTEGER NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PENDING','CLAIMED','STOPPED')),
 attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 5),
 max_attempts INTEGER NOT NULL CHECK(max_attempts BETWEEN 1 AND 5),
 next_attempt_at TEXT NOT NULL,
 lease_token TEXT,
 lease_until TEXT,
 updated_at TEXT NOT NULL,
 UNIQUE(user_id,task_uuid)
);
CREATE INDEX idx_native_retry_due ON torrent_native_retry_plan(status,next_attempt_at);
