-- Owner-scoped vetted public tracker URLs, never stored in global metadata.
ALTER TABLE torrent_native_task ADD COLUMN tracker_urls_json TEXT;
