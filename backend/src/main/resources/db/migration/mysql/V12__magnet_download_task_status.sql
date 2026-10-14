-- 下载到 100% 时落库的完成记录：状态、完成时间、总大小
ALTER TABLE magnet_download_task
    ADD COLUMN status VARCHAR(16) NULL,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD COLUMN total_size BIGINT NULL;
