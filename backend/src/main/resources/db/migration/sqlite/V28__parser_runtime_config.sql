CREATE TABLE parser_runtime_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key VARCHAR(128) NOT NULL,
    config_value TEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_url VARCHAR(512),
    last_verified_at DATETIME,
    last_refreshed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_parser_runtime_config_key ON parser_runtime_config (config_key, deleted);
