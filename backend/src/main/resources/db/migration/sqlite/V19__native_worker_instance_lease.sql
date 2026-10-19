-- Observability-only worker instance leases; expiry never grants automatic task ownership.
CREATE TABLE torrent_native_worker_instance (
 instance_id TEXT PRIMARY KEY,
 lease_token TEXT NOT NULL,
 lease_until TEXT NOT NULL,
 last_heartbeat_at TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('ACTIVE','STOPPED'))
);
CREATE INDEX idx_native_worker_instance_expiry ON torrent_native_worker_instance(status,lease_until);
