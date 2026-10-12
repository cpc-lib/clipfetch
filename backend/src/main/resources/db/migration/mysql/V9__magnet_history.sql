-- ============================================================
-- V9 磁力解析历史表（MySQL 5.7+ / 8.0，utf8mb4）
-- 保存磁力链接解析出的元数据与文件清单；下载任务状态由 qBittorrent 管理
-- ============================================================

CREATE TABLE IF NOT EXISTS `magnet_history`
(
    `id`         BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT        NOT NULL COMMENT '所属用户 ID',
    `info_hash`  VARCHAR(64)   NOT NULL COMMENT '磁力 Info Hash（btih 40 位十六进制）',
    `magnet_uri` TEXT          NOT NULL COMMENT '磁力链接',
    `name`       VARCHAR(512)  DEFAULT NULL COMMENT '种子名称',
    `total_size` BIGINT        DEFAULT NULL COMMENT '总大小（字节）',
    `files_json` TEXT          DEFAULT NULL COMMENT '文件清单 JSON（index/path/size）',
    `status`     VARCHAR(32)   NOT NULL COMMENT '解析状态：PARSING/SUCCEEDED/FAILED',
    `error`      VARCHAR(1024) DEFAULT NULL COMMENT '解析失败原因',
    `deleted`    BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 非0=已删除（值为记录 id）',
    `created_at` DATETIME(6)   NOT NULL COMMENT '创建时间',
    `updated_at` DATETIME(6)   NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_magnet_history_user_hash_deleted` (`user_id`, `info_hash`, `deleted`),
    KEY `idx_magnet_history_user` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='磁力解析历史';
