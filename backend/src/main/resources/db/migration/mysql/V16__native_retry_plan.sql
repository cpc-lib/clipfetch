-- Native durable retry intent only. No unattended worker activation in this migration.
CREATE TABLE torrent_native_retry_plan (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_uuid CHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 expected_revision BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL,
 attempt_count INT NOT NULL DEFAULT 0,
 max_attempts INT NOT NULL,
 next_attempt_at DATETIME(6) NOT NULL,
 lease_token CHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_native_retry_owner_task(user_id,task_uuid),
 KEY idx_native_retry_due(status,next_attempt_at),
 CONSTRAINT chk_native_retry_attempt CHECK(attempt_count BETWEEN 0 AND 5 AND max_attempts BETWEEN 1 AND 5),
 CONSTRAINT chk_native_retry_state CHECK(status IN ('PENDING','CLAIMED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
