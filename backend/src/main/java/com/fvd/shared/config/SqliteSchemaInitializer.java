package com.fvd.shared.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * SQLite 数据源初始化：首次启动自动创建数据目录和表结构。
 * 仅当最终生效的 DB_URL 为 jdbc:sqlite: 时执行；MySQL 等其他数据源直接跳过。
 * 在 Spring 容器启动前调用（Hikari 懒加载，首次取连接时目录必须已存在）。
 */
public final class SqliteSchemaInitializer {

    private static final String DEFAULT_URL = "jdbc:sqlite:data/fvd.db";
    private static final String PREFIX = "jdbc:sqlite:";

    private static final String[] DDL = {
            // 用户表
            "CREATE TABLE IF NOT EXISTS user ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " email VARCHAR(128) NOT NULL,"
                    + " password_hash VARCHAR(100) NOT NULL,"
                    + " nickname VARCHAR(64),"
                    + " vip INTEGER NOT NULL DEFAULT 0,"
                    + " created_at TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_user_email ON user(email)",
            // Refresh Token 表
            "CREATE TABLE IF NOT EXISTS refresh_token ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " user_id INTEGER NOT NULL,"
                    + " token VARCHAR(64) NOT NULL,"
                    + " expires_at TEXT NOT NULL,"
                    + " revoked INTEGER NOT NULL DEFAULT 0,"
                    + " created_at TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_refresh_token ON refresh_token(token)",
            "CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_token(user_id)",
            // AI 每日使用量表
            "CREATE TABLE IF NOT EXISTS ai_usage ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " user_id INTEGER NOT NULL,"
                    + " usage_date TEXT NOT NULL,"
                    + " count INTEGER NOT NULL DEFAULT 0)",
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_usage_user_date ON ai_usage(user_id, usage_date)",
            // 用户平台 Cookies 表
            "CREATE TABLE IF NOT EXISTS user_cookie ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " user_id INTEGER NOT NULL,"
                    + " platform VARCHAR(16) NOT NULL,"
                    + " content TEXT NOT NULL,"
                    + " valid INTEGER NOT NULL DEFAULT 1,"
                    + " status_message VARCHAR(255),"
                    + " last_verified_at TEXT,"
                    + " last_used_at TEXT,"
                    + " created_at TEXT NOT NULL,"
                    + " updated_at TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_user_cookie_user_platform ON user_cookie(user_id, platform)"
    };

    private SqliteSchemaInitializer() {
    }

    /**
     * 若当前数据源是 SQLite：创建 db 文件所在目录并执行建表 DDL（幂等）。
     * 数据库文件路径取自 DB_URL 系统属性/环境变量，缺省为 data/fvd.db（与 application.yml 一致）。
     */
    public static void initIfSqlite() {
        String url = System.getProperty("DB_URL");
        if (url == null || url.isBlank()) {
            url = System.getenv("DB_URL");
        }
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
        }
        if (!url.startsWith(PREFIX)) {
            return;
        }
        try {
            Path dbFile = Path.of(url.substring(PREFIX.length()));
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            try (Connection conn = DriverManager.getConnection(url);
                 Statement st = conn.createStatement()) {
                for (String ddl : DDL) {
                    st.execute(ddl);
                }
            }
            System.out.println("SQLite 数据源已就绪: " + dbFile.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("SQLite 初始化失败（不阻塞启动，首次访问数据库时会报错）: " + e.getMessage());
        }
    }
}
