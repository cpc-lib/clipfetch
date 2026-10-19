-- Durable, deduplicated task-owner recovery audit; does not dispatch work.
CREATE TABLE torrent_native_recovery_audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL,
 task_uuid TEXT NOT NULL,
 expected_revision INTEGER NOT NULL,
 execution_epoch INTEGER NOT NULL,
 execution_instance_id TEXT NOT NULL,
 outcome TEXT NOT NULL,
 recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,task_uuid,expected_revision,outcome)
);
CREATE INDEX idx_native_recovery_audit_task ON torrent_native_recovery_audit(user_id,task_uuid);
