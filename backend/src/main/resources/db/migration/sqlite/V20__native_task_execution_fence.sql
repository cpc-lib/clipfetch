-- A task execution generation is monotonically increasing; never reuse an old epoch.
ALTER TABLE torrent_native_task ADD COLUMN execution_epoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE torrent_native_task ADD COLUMN execution_instance_id TEXT;
