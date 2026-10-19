package cc.ivera;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@MapperScan("cc.ivera.**.domain")
@SpringBootApplication
public class ClipFetchApplication {

    public static void main(String[] args) {
        // 优先加载 backend/.env，再依次尝试 CWD 与上级目录兜底
        Path backendDir = resolveBackendDir();
        if (backendDir != null) {
            loadDotEnv(backendDir.resolve(".env"));
        }
        loadDotEnv(Path.of(".env"));
        loadDotEnv(Path.of("../.env"));
        // 数据源预处理：SQLite 创建 db 文件目录，MySQL 切换 Flyway 迁移目录；建表统一由 Flyway 执行
        prepareDatasource();
        // 清理上次进程被强杀遗留的临时文件（含用户 cookies 明文），避免长期滞留在系统临时目录
        cleanupTempResidue();
        SpringApplication.run(ClipFetchApplication.class, args);
    }

    /** 本应用在系统临时目录写入的临时文件前缀（cookies 临时文件与解析中间产物） */
    private static final String[] TEMP_RESIDUE_PREFIXES = {
            "clipfetch-ytdlp-cookie-", "clipfetch-tencent-", "clipfetch-frame-",
            // 旧版本使用 fvd- 前缀，保留兼容清理，避免历史残留长期滞留
            "fvd-ytdlp-cookie-", "fvd-tencent-", "fvd-frame-"
    };

    /** 仅清理超过该时长（毫秒）的残留：避免误删其它运行中实例正在使用的临时文件 */
    private static final long TEMP_RESIDUE_MIN_AGE_MS = 10 * 60 * 1000L;

    /**
     * 启动清扫：正常路径下这些文件都在 finally 里删除了，进程被强杀时才会留下，
     * 其中 cookies 临时文件含用户 cookie 明文，不应长期滞留磁盘。
     * 只处理临时目录下的普通文件，脚本解压目录（clipfetch-cctv 等）不受影响。
     */
    private static void cleanupTempResidue() {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir", "."));
        if (!Files.isDirectory(tmpDir)) {
            return;
        }
        long deadline = System.currentTimeMillis() - TEMP_RESIDUE_MIN_AGE_MS;
        int removed = 0;
        try (java.util.stream.Stream<Path> files = Files.list(tmpDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!isTempResidue(name)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < deadline && Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        if (removed > 0) {
            System.out.println("已清理临时残留文件 " + removed + " 个");
        }
    }

    /** 按前缀匹配残留文件；历史固定文件名 clipfetch-tencent-cookies.txt 也落在 clipfetch-tencent- 前缀内 */
    private static boolean isTempResidue(String name) {
        for (String prefix : TEMP_RESIDUE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
            Path codePath = Path.of(ClipFetchApplication.class.getProtectionDomain()
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
