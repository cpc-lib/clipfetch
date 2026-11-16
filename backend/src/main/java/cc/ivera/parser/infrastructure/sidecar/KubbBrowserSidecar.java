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
 * 酷播（kubb.tv）浏览器登录态 sidecar（Python + Playwright + 系统 Chrome）。
 *
 * <p>酷播 1080p / 720p 为 VIP 专属，接口按登录态回退到游客 576p；登录页接
 * Cloudflare Turnstile，无法在服务端模拟。本组件按需拉起常驻 Python 进程
 * （{@code kubb_sidecar.py}），弹出有头 Chrome 让用户手动登录一次，登录 token
 * （uid/expire/gid/sign/token）保存在页面 sessionStorage，由 sidecar 读出后交给
 * {@link cc.ivera.parser.application.parser.video.IfyTvParser} 拼进接口请求。
 */
@Slf4j
@Component
public class KubbBrowserSidecar {

    private final boolean enabled;
    private final String pythonExe;
    private final int port;
    private final String profileDir;
    private final String scriptPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Process process;

    public KubbBrowserSidecar(@Value("${app.kubb-sidecar.enabled:true}") boolean enabled,
                              @Value("${app.python-exe:python}") String pythonExe,
                              @Value("${app.kubb-sidecar.port:8099}") int port,
                              @Value("${app.kubb-sidecar.profile-dir:kubb-profile}") String profileDir,
                              @Value("${app.kubb-sidecar.script:}") String script) {
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
     * 弹出有头 Chrome 等用户手动登录（sidecar 内部最多等 300 秒）。
     */
    public synchronized void login() {
        if (!enabled) {
            throw new BusinessException("酷播浏览器登录未启用（app.kubb-sidecar.enabled=false）");
        }
        ensureRunning();
        JsonNode node = post("/login", Duration.ofSeconds(330));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("酷播登录未完成：" + node.path("error").asText("超时或被取消，请重试"));
        }
    }

    /**
     * 清除登录态，便于更换账号后重新登录。
     */
    public synchronized void logout() {
        if (!enabled) {
            throw new BusinessException("酷播浏览器登录未启用");
        }
        ensureRunning();
        JsonNode node = post("/logout", Duration.ofSeconds(120));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("酷播清除登录态失败：" + node.path("error").asText("未知错误"));
        }
    }

    /**
     * 取当前登录 token（未登录时为 null）。
     *
     * @return token 对象（uid/expire/gid/sign/token），未登录返回 null
     */
    public synchronized JsonNode token() {
        if (!enabled) {
            return null;
        }
        // 被动检查：sidecar 未运行则视为未登录，不主动拉起 Chrome（避免下载链路弹窗阻塞）。
        // 只有用户主动点"登录 VIP"时 login() 才会 ensureRunning。
        if (!health()) {
            return null;
        }
        try {
            // /token 仅被动读取当前登录态（毫秒级返回），超时设短避免下载链路被阻塞
            JsonNode node = get("/token", Duration.ofSeconds(15));
            if (!node.path("ok").asBoolean(false)) {
                log.warn("酷播 sidecar 读取登录态失败: {}", node.path("error").asText());
                return null;
            }
            return node.path("logged_in").asBoolean(false) ? node.path("token") : null;
        } catch (Exception e) {
            log.warn("酷播 sidecar 读取登录态失败: {}", e.getMessage());
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
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("等待酷播 sidecar 启动被中断");
            }
            if (health()) {
                log.info("酷播 sidecar 已就绪 (port={}, profile={})", port, profileDir);
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new BusinessException("酷播 sidecar 进程启动失败，请检查 Python 环境及 playwright 是否安装（pip install playwright）");
            }
        }
        throw new BusinessException("酷播 sidecar 启动超时（90 秒）");
    }

    private boolean health() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(30)).GET().build();
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
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExe, scriptPath,
                    "--port", String.valueOf(port),
                    "--profile-dir", profileDir);
            pb.redirectErrorStream(true);
            process = pb.start();
            Process p = process;
            Thread.ofVirtual().name("kubb-sidecar-log").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[kubb-sidecar] {}", line);
                    }
                } catch (Exception ignored) {
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (p.isAlive()) {
                    p.destroy();
                }
            }));
            log.info("启动酷播 sidecar: {} {} --port {} --profile-dir {}",
                    pythonExe, scriptPath, port, profileDir);
        } catch (Exception e) {
            throw new BusinessException("无法启动酷播 sidecar（" + pythonExe + " " + scriptPath + "）："
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
                throw new BusinessException("酷播 sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与酷播 sidecar 通信失败：" + e.getMessage());
        }
    }

    private JsonNode post(String path, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = mapper.readTree(resp.body());
            if (resp.statusCode() != 200) {
                throw new BusinessException("酷播 sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与酷播 sidecar 通信失败：" + e.getMessage());
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
            candidates.add(Path.of("src", "main", "resources", "kubb", "kubb_sidecar.py"));
            candidates.add(Path.of("backend", "src", "main", "resources", "kubb", "kubb_sidecar.py"));
        }
        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath().toString();
            }
        }
        try {
            java.net.URL url = KubbBrowserSidecar.class.getClassLoader().getResource("kubb/kubb_sidecar.py");
            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    return Path.of(url.toURI()).toAbsolutePath().toString();
                }
                Path out = Path.of(System.getProperty("java.io.tmpdir"),
                        "clipfetch-kubb", "kubb_sidecar.py");
                Files.createDirectories(out.getParent());
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return out.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("解压 kubb_sidecar.py 失败: " + e.getMessage(), e);
        }
        throw new IllegalStateException("找不到 kubb_sidecar.py，请检查 app.kubb-sidecar.script 配置");
    }
}
