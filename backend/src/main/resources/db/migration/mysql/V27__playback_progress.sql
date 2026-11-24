CREATE TABLE playback_progress (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '所属用户 ID',
    source_key VARCHAR(64) NOT NULL COMMENT '归一化 URL 的 SHA-256',
    source_url VARCHAR(2048) NOT NULL COMMENT '原始播放链接',
    platform VARCHAR(64) NULL COMMENT '平台标识',
    title VARCHAR(512) NULL COMMENT '标题快照',
    format_id VARCHAR(256) NULL COMMENT '最近播放清晰度',
    mode VARCHAR(16) NULL COMMENT 'progressive/hls/merge',
    position_seconds INT NOT NULL DEFAULT 0 COMMENT '上次位置（秒）',
    duration_seconds INT NULL COMMENT '总时长（秒）',
    completed TINYINT NOT NULL DEFAULT 0 COMMENT '1=已看完',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 非0=记录 id',
    UNIQUE KEY uk_playback_progress_user_key (user_id, source_key, deleted),
    KEY idx_playback_progress_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='播放进度记忆';
