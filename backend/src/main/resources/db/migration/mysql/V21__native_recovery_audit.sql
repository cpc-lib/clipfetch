-- Durable, deduplicated task-owner recovery audit; does not dispatch work.
CREATE TABLE torrent_native_recovery_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 task_uuid CHAR(36) NOT NULL,
 expected_revision BIGINT NOT NULL,
 execution_epoch BIGINT NOT NULL,
 execution_instance_id CHAR(36) NOT NULL,
 outcome VARCHAR(24) NOT NULL,
 recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_native_recovery_event(user_id,task_uuid,expected_revision,outcome),
 INDEX idx_native_recovery_audit_task(user_id,task_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
