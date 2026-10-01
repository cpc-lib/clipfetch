package com.fvd;

import com.fvd.shared.config.SqliteSchemaInitializer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@MapperScan({"com.fvd.auth.domain", "com.fvd.cookie.domain"})
@SpringBootApplication
public class VideoDownloaderApplication {

    public static void main(String[] args) {
        // 依次尝试：CWD、CWD 上级（mvn 从 backend/ 启动）、代码所在的 backend 目录（IDEA 从项目根启动）
        loadDotEnv(Path.of(".env"));
        loadDotEnv(Path.of("../.env"));
        Path backendDir = resolveBackendDir();
        if (backendDir != null) {
            loadDotEnv(backendDir.resolve(".env"));
        }
        // 默认数据源为 SQLite：首次启动自动建表；.env 配置 DB_URL 为 MySQL 时此方法自动跳过
        SqliteSchemaInitializer.initIfSqlite();
        // MySQL 数据源启用 Flyway 自动迁移（.env 中显式设置 FLYWAY_ENABLED 时以 .env 为准）
        enableFlywayIfNotSqlite();
        SpringApplication.run(VideoDownloaderApplication.class, args);
    }

    /**
     * 最终生效的 DB_URL 不是 SQLite 时启用 Flyway（MySQL 建表/迁移走 db/migration/V*.sql）。
     */
    private static void enableFlywayIfNotSqlite() {
        if (System.getProperty("FLYWAY_ENABLED") != null || System.getenv("FLYWAY_ENABLED") != null) {
            return;
        }
        String url = System.getProperty("DB_URL");
        if (url == null || url.isBlank()) {
            url = System.getenv("DB_URL");
        }
        if (url != null && !url.isBlank() && !url.startsWith("jdbc:sqlite:")) {
            System.setProperty("FLYWAY_ENABLED", "true");
        }
    }

    /**
     * 根据类所在位置推导 backend 目录：
     * IDE 运行为 backend/target/classes；打包运行为 backend/target/*.jar。
     */
    private static Path resolveBackendDir() {
        try {
            Path codePath = Path.of(VideoDownloaderApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = codePath.getParent().getParent();
            return Files.isDirectory(dir) ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 加载 backend/.env 到系统属性，供 application.yml 占位符读取（DB/JWT/DeepSeek/代理等）。
     */
    private static void loadDotEnv(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Properties props = new Properties();
            for (String line : Files.readAllLines(file)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\""))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (System.getProperty(key) == null && System.getenv(key) == null) {
                    System.setProperty(key, value);
                }
            }
            props.clear();
        } catch (IOException ignored) {
        }
    }
}
