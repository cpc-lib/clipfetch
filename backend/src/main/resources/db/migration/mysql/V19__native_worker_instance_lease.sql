-- Observability-only worker instance leases; expiry never grants automatic task ownership.
CREATE TABLE torrent_native_worker_instance (
 instance_id CHAR(36) PRIMARY KEY,
 lease_token CHAR(36) NOT NULL,
 lease_until DATETIME(6) NOT NULL,
 last_heartbeat_at DATETIME(6) NOT NULL,
 status VARCHAR(16) NOT NULL,
 INDEX idx_native_worker_instance_expiry(status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
