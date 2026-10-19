-- Persist worker binding; does not enable automatic scheduling.
ALTER TABLE torrent_native_retry_plan ADD COLUMN worker_job_uuid TEXT;
