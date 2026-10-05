package com.fvd;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@MapperScan({"com.fvd.auth.domain", "com.fvd.cookie.domain", "com.fvd.thirdparty.domain"})
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
        // 数据源预处理：SQLite 创建 db 文件目录，MySQL 切换 Flyway 迁移目录；建表统一由 Flyway 执行
        prepareDatasource();
        SpringApplication.run(VideoDownloaderApplication.class, args);
    }

    /**
     * 根据最终生效的 DB_URL 做启动前准备：
     * SQLite：创建 db 文件所在目录（Flyway 连接前目录必须已存在）；
     * MySQL：切换 Flyway 迁移目录到 db/migration/mysql（.env 显式设置 FLYWAY_LOCATIONS 时以 .env 为准）。
     */
    private static void prepareDatasource() {
        String url = System.getProperty("DB_URL");
        if (url == null || url.isBlank()) {
            url = System.getenv("DB_URL");
        }
        boolean isSqlite = url == null || url.isBlank() || url.startsWith("jdbc:sqlite:");
        if (isSqlite) {
            String sqliteUrl = (url == null || url.isBlank()) ? "jdbc:sqlite:data/fvd.db" : url;
            try {
                Path dbFile = Path.of(sqliteUrl.substring("jdbc:sqlite:".length()));
                if (dbFile.getParent() != null) {
                    Files.createDirectories(dbFile.getParent());
                }
            } catch (Exception e) {
                System.err.println("SQLite 数据目录创建失败: " + e.getMessage());
            }
        } else if (System.getProperty("FLYWAY_LOCATIONS") == null && System.getenv("FLYWAY_LOCATIONS") == null) {
            System.setProperty("FLYWAY_LOCATIONS", "classpath:db/migration/mysql");
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
                // 支持 %VAR% 引用系统环境变量（如 FFMPEG_LOCATION=%FFMPEG%）；变量不存在则跳过该项，回落 yml 默认值
                if (value.contains("%")) {
                    String expanded = expandEnvVars(value);
                    if (expanded == null) {
                        continue;
                    }
                    value = expanded;
                }
                if (System.getProperty(key) == null && System.getenv(key) == null) {
                    System.setProperty(key, value);
                }
            }
            props.clear();
        } catch (IOException ignored) {
        }
    }

    /**
     * 展开值中的 %VAR% 占位符为系统环境变量值；任一变量不存在时返回 null（调用方跳过该项）。
     */
    private static String expandEnvVars(String value) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([^%]+)%").matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String envValue = System.getenv(m.group(1));
            if (envValue == null) {
                return null;
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(envValue));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
