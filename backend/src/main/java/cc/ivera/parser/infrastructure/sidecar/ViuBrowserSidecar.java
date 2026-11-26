package cc.ivera.parser.infrastructure.sidecar;

import cc.ivera.shared.web.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Viu（viu.com）浏览器登录与取流 sidecar（Python + Playwright + 系统 Chrome）。
 *
 * <p>参照 {@link KubbBrowserSidecar} 与 {@link TencentBrowserSidecar}：
 * <ul>
 *   <li>调用 {@link #login()} 时按需启动 {@code viu_sidecar.py} 并弹出有头 Chrome，
 *       用户在真实浏览器中完成 Viu 登录（含 Google/Facebook/邮箱验证），登录态持久化在 {@code viu-profile}；</li>
 *   <li>解析/下载/在线播放调用 {@link #session()} 被动读取当前有效 {@code token}、{@code user_level}
 *       及 {@code Cookie}，无需手动安装浏览器插件导出 {@code cookies.txt}；</li>
 *   <li>后端重启后若检测到 {@code viu-profile/.sidecar_cookies.json} 已有持久化会话，
 *       自动静默拉起无头 sidecar 恢复登录态，游客未登录时零开销跳过。</li>
 * </ul>
 */
@Slf4j
@Component
public class ViuBrowserSidecar {

    private final boolean enabled;
    private final String pythonExe;
    private final int port;
    private final String profileDir;
    private final String scriptPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Process process;

    public ViuBrowserSidecar(@Value("${app.viu-sidecar.enabled:true}") boolean enabled,
                             @Value("${app.python-exe:python}") String pythonExe,
                             @Value("${app.viu-sidecar.port:8100}") int port,
                             @Value("${app.viu-sidecar.profile-dir:viu-profile}") String profileDir,
                             @Value("${app.viu-sidecar.script:}") String script) {
        this.enabled = enabled;
        this.pythonExe = pythonExe;
        this.port = port;
        this.profileDir = Path.of(profileDir).toAbsolutePath().toString();
        this.scriptPath = resolveScript(script);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 弹出有头 Chrome 等用户手动登录 Viu（最长等待 300 秒）。
     */
    public synchronized void login() {
        if (!enabled) {
            throw new BusinessException("Viu 浏览器登录未启用（app.viu-sidecar.enabled=false）");
        }
        ensureRunning();
        JsonNode node = post("/login", Duration.ofSeconds(335));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("Viu 登录未完成：" + node.path("error").asText("超时或窗口已关闭，请重试"));
        }
    }

    /**
     * 清除 Viu 浏览器持久化登录态，便于切换账号。
     */
    public synchronized void logout() {
        if (!enabled) {
            throw new BusinessException("Viu 浏览器登录未启用");
        }
        ensureRunning();
        JsonNode node = post("/logout", Duration.ofSeconds(120));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("Viu 清除登录态失败：" + node.path("error").asText("未知错误"));
        }
    }

    /**
     * 读取当前已登录的 Viu 会话；未登录时返回 {@code null}。
     * 若 sidecar 尚未启动但 profile 目录中存在历史登录备份，自动拉起无头 sidecar 恢复会话。
     */
    public synchronized ViuSession session() {
        if (!enabled) {
            return null;
        }
        if (!health()) {
            Path backup = Path.of(profileDir, ".sidecar_cookies.json");
            if (!Files.exists(backup)) {
                return null;
            }
            try {
                ensureRunning();
            } catch (Exception e) {
                log.warn("Viu sidecar 自动恢复启动失败，降级游客态: {}", e.getMessage());
                return null;
            }
        }
        try {
            JsonNode node = get("/session", Duration.ofSeconds(45));
            if (!node.path("ok").asBoolean(false) || !node.path("logged_in").asBoolean(false)) {
                return null;
            }
            String token = node.path("token").asText(null);
            if (token == null || token.isBlank()) {
                return null;
            }
            int userLevel = Math.max(1, node.path("user_level").asInt(1));
            String cookieHeader = node.path("cookie_header").asText("");
            String cookiesTxt = node.path("cookies_txt").asText("");
            return new ViuSession(token, userLevel,
                    cookieHeader.isBlank() ? null : cookieHeader,
                    cookiesTxt.isBlank() ? null : cookiesTxt);
        } catch (Exception e) {
            log.warn("读取 Viu sidecar 登录态失败: {}", e.getMessage());
            return null;
        }
    }

    // ===== 进程管理 =====

    private void ensureRunning() {
        if (health()) {
            return;
        }
        startProcess();
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("等待 Viu sidecar 启动被中断");
            }
            if (health()) {
                log.info("Viu sidecar 已就绪 (port={}, profile={})", port, profileDir);
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new BusinessException("Viu sidecar 进程启动失败，请检查 Python 环境及 playwright 是否安装（pip install playwright）");
            }
        }
        throw new BusinessException("Viu sidecar 启动超时（90 秒）");
    }

    private boolean health() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 && mapper.readTree(resp.body()).path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void startProcess() {
        if (process != null && process.isAlive()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(profileDir));
            List<String> cmd = List.of(
                    pythonExe, scriptPath,
                    "--port", String.valueOf(port),
                    "--profile-dir", profileDir);
            log.info("启动 Viu sidecar: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // 移除宿主机欧美代理环境变量，防止 Chrome 打开 Viu 被重定向到 /ott/no-service/
            pb.environment().remove("HTTP_PROXY");
            pb.environment().remove("HTTPS_PROXY");
            pb.environment().remove("ALL_PROXY");
            pb.environment().remove("http_proxy");
            pb.environment().remove("https_proxy");
            pb.environment().remove("all_proxy");
            pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            process = pb.start();
            Process p = process;
            Thread.ofVirtual().name("viu-sidecar-log").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[viu-sidecar] {}", line);
                    }
                } catch (Exception ignored) {
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (p.isAlive()) {
                    p.destroy();
                }
            }));
        } catch (Exception e) {
            throw new BusinessException("无法启动 Viu sidecar（" + pythonExe + " " + scriptPath + "）："
                    + e.getMessage() + "。请确认已安装 Python 和 playwright（pip install playwright）。");
        }
    }

    private JsonNode get(String path, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(timeout)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = mapper.readTree(resp.body());
            if (resp.statusCode() != 200) {
                throw new BusinessException("Viu sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与 Viu sidecar 通信失败：" + e.getMessage());
        }
    }

    private JsonNode post(String path, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = mapper.readTree(resp.body());
            if (resp.statusCode() != 200) {
                throw new BusinessException("Viu sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与 Viu sidecar 通信失败：" + e.getMessage());
        }
    }

    private static String resolveScript(String configured) {
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            candidates.add(p);
            if (!p.isAbsolute()) {
                candidates.add(Path.of("backend").resolve(p));
            }
        } else {
            candidates.add(Path.of("src", "main", "resources", "viu", "viu_sidecar.py"));
            candidates.add(Path.of("backend", "src", "main", "resources", "viu", "viu_sidecar.py"));
        }
        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath().toString();
            }
        }
        try {
            java.net.URL url = ViuBrowserSidecar.class.getClassLoader().getResource("viu/viu_sidecar.py");
            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    return Path.of(url.toURI()).toAbsolutePath().toString();
                }
                Path out = Path.of(System.getProperty("java.io.tmpdir"),
                        "clipfetch-viu", "viu_sidecar.py");
                Files.createDirectories(out.getParent());
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return out.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("解压 viu_sidecar.py 失败: " + e.getMessage(), e);
        }
        throw new IllegalStateException("找不到 viu_sidecar.py，请检查 app.viu-sidecar.script 配置");
    }

    public record ViuSession(String token, int userLevel, String cookieHeader, String cookiesTxt) {
    }
}
