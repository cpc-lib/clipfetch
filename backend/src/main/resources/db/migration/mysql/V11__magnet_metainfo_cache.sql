-- Persist exact .torrent metainfo for recovery if qBittorrent loses torrent metadata.
ALTER TABLE magnet_history ADD COLUMN torrent_file_b64 LONGTEXT NULL;
