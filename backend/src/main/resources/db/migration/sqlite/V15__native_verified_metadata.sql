-- Owner-bound full BEP9 metainfo; legacy summaries remain readable.
ALTER TABLE torrent_native_parse_result ADD COLUMN verified_torrent_bytes BLOB;
