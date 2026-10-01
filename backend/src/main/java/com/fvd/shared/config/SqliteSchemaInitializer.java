package com.fvd.shared.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * SQLite 数据源初始化：首次启动自动创建数据目录和表结构。
 * 仅当最终生效的 DB_URL 为 jdbc:sqlite: 时执行；MySQL 等其他数据源直接跳过。
 * 在 Spring 容器启动前调用（Hikari 懒加载，首次取连接时目录必须已存在）。
 */
public final class SqliteSchemaInitializer {

    private static final String DEFAULT_URL = "jdbc:sqlite:data/fvd.db";
    private static final String PREFIX = "jdbc:sqlite:";
    private static final String SCHEMA_PATH = "db/migration/sqlite/V1__init_schema.sql";

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
            String sql = loadSchemaSql();
            if (sql == null || sql.isBlank()) {
                System.err.println("SQLite 初始化失败：未找到 classpath 下 " + SCHEMA_PATH + "，跳过自动建表");
                return;
            }
            try (Connection conn = DriverManager.getConnection(url);
                 Statement st = conn.createStatement()) {
                for (String stmt : sql.split(";")) {
                    // 逐行剔除注释与空行后重新拼接，再执行
                    String stmtSql = stmt.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                            .collect(Collectors.joining(" "));
                    if (!stmtSql.isEmpty()) {
                        st.execute(stmtSql);
                    }
                }
            }
            System.out.println("SQLite 数据源已就绪: " + dbFile.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("SQLite 初始化失败（不阻塞启动，首次访问数据库时会报错）: " + e.getMessage());
        }
    }

    /**
     * 从 classpath 读取 SQLite 建表脚本内容（优先类加载器）。
     */
    private static String loadSchemaSql() {
        ClassLoader cl = SqliteSchemaInitializer.class.getClassLoader();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(cl.getResourceAsStream(SCHEMA_PATH)))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return null;
        }
    }
}
