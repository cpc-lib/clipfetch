CREATE TABLE parser_runtime_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL COMMENT '配置键，如 netmirror.tmdb_keys',
    config_value TEXT NOT NULL COMMENT '配置值（JSON 字符串）',
    source VARCHAR(32) NOT NULL COMMENT '来源：AUTO_FETCH / MANUAL',
    source_url VARCHAR(512) NULL COMMENT '自动提取时的来源 JS URL',
    last_verified_at DATETIME NULL COMMENT '最近一次校验可用时间',
    last_refreshed_at DATETIME NULL COMMENT '最近一次抓取或覆盖时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 非0=记录 id',
    UNIQUE KEY uk_parser_runtime_config_key (config_key, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析器运行态动态配置';
