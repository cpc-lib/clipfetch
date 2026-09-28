-- ============================================================
-- 万能视频下载器（fvd）MySQL 建表脚本
-- 对应 JPA 实体：User / RefreshToken / AiUsage
-- 适用 MySQL 5.7+ / 8.0，字符集 utf8mb4
-- ============================================================

CREATE
DATABASE IF NOT EXISTS `fvd`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE
`fvd`;

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `email`
    VARCHAR
(
    128
) NOT NULL COMMENT '邮箱（登录账号）',
    `password_hash` VARCHAR
(
    100
) NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname` VARCHAR
(
    64
) DEFAULT NULL COMMENT '昵称',
    `vip` TINYINT
(
    1
) NOT NULL DEFAULT 0 COMMENT '是否 VIP（VIP 不限 AI 配额）',
    `created_at` DATETIME
(
    6
) NOT NULL COMMENT '注册时间',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_user_email`
(
    `email`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci
    COMMENT ='用户表';

-- ------------------------------------------------------------
-- Refresh Token 表（双 token 认证，刷新时旋转失效）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `refresh_token`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `user_id`
    BIGINT
    NOT
    NULL
    COMMENT
    '所属用户 id',
    `token`
    VARCHAR
(
    64
) NOT NULL COMMENT '随机 64 位 hex refresh token',
    `expires_at` DATETIME
(
    6
) NOT NULL COMMENT '过期时间（默认 7 天）',
    `revoked` TINYINT
(
    1
) NOT NULL DEFAULT 0 COMMENT '是否已失效（旋转/登出后置 1）',
    `created_at` DATETIME
(
    6
) NOT NULL COMMENT '签发时间',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `idx_refresh_token`
(
    `token`
),
    KEY `idx_refresh_user`
(
    `user_id`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci
    COMMENT ='Refresh Token 表';

-- ------------------------------------------------------------
-- AI 每日使用量表（免费用户每日限额，默认 3 次/天）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_usage`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `user_id`
    BIGINT
    NOT
    NULL
    COMMENT
    '用户 id',
    `usage_date`
    DATE
    NOT
    NULL
    COMMENT
    '使用日期（按天统计）',
    `count`
    INT
    NOT
    NULL
    DEFAULT
    0
    COMMENT
    '当日已用次数',
    PRIMARY
    KEY
(
    `id`
),
    UNIQUE KEY `uk_ai_usage_user_date`
(
    `user_id`,
    `usage_date`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci
    COMMENT ='AI 每日使用量表';

-- ------------------------------------------------------------
-- 用户平台 Cookies 表（抖音 / Instagram 的登录 cookies，用户自助上传维护）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_cookie`
(
    `id`
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT,
    `user_id`
    BIGINT
    NOT
    NULL
    COMMENT
    '所属用户 id',
    `platform`
    VARCHAR
(
    16
) NOT NULL COMMENT '平台标识：douyin / instagram',
    `content` MEDIUMTEXT NOT NULL COMMENT 'Netscape cookies.txt 原文',
    `valid` TINYINT
(
    1
) NOT NULL DEFAULT 1 COMMENT '最近校验/使用是否有效',
    `status_message` VARCHAR
(
    255
) DEFAULT NULL COMMENT '最近状态说明或失效原因',
    `last_verified_at` DATETIME
(
    6
) DEFAULT NULL COMMENT '最近校验时间',
    `last_used_at` DATETIME
(
    6
) DEFAULT NULL COMMENT '最近用于下载的时间',
    `created_at` DATETIME
(
    6
) NOT NULL COMMENT '首次上传时间',
    `updated_at` DATETIME
(
    6
) NOT NULL COMMENT '最近更新时间',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_user_cookie_user_platform`
(
    `user_id`,
    `platform`
)
    ) ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci
    COMMENT ='用户平台 Cookies 表';
