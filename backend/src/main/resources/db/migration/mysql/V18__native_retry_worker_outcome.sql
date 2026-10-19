-- Terminal worker outcome is persisted only after fenced job status lookup.
ALTER TABLE torrent_native_retry_plan ADD COLUMN worker_outcome VARCHAR(16) NULL;
