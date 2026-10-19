-- ============================================================
-- V22 用户自选 Native 下载根目录（MySQL）
-- 只保存用户在服务端批准目录范围内挑选的目录；删除一律 deleted = id
-- ============================================================

CREATE TABLE IF NOT EXISTS native_download_root (
    id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    biz_key    VARCHAR(64) NOT NULL DEFAULT 'default',
    root_path  VARCHAR(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted    BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_native_download_root_active (user_id, biz_key, deleted),
    KEY idx_native_download_root_user (user_id, deleted, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
