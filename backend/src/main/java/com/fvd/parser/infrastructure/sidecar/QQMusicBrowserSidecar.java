package com.fvd.parser.infrastructure.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
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
import java.util.Map;

/**
 * QQ 音乐浏览器取流 sidecar（Python + Playwright + 系统 Chrome）。
 *
 * <p>vkey 接口已改用 TmeWebSec 签名/加密通道，签名器只存在于真实页面 JS 上下文中，
 * 纯 HTTP 无法复刻。本组件按需拉起常驻 Python 进程（{@code qqmusic_sidecar.py}），
 * 通过 127.0.0.1 HTTP 取各品质直链；登录态保存在 Chrome 持久化用户目录，
 * 过期时调用 {@link #login()} 会弹出有头浏览器供扫码。
 */
@Slf4j
@Component
public class QQMusicBrowserSidecar {

    private final boolean enabled;
    private final String pythonExe;
    private final int port;
    private final String profileDir;
    private final String scriptPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Process process;

    public QQMusicBrowserSidecar(@Value("${app.qqmusic-sidecar.enabled:true}") boolean enabled,
                                 @Value("${app.python-exe:python}") String pythonExe,
                                 @Value("${app.qqmusic-sidecar.port:8095}") int port,
                                 @Value("${app.qqmusic-sidecar.profile-dir:qqmusic-profile}") String profileDir,
                                 @Value("${app.qqmusic-sidecar.script:}") String script) {
        this.enabled = enabled;
        this.pythonExe = pythonExe;
        this.port = port;
        this.profileDir = Path.of(profileDir).toAbsolutePath().toString();
        this.scriptPath = resolveScript(script);
    }

    /** sidecar 返回的某品质结果 */
    public record Quality(int result, String url) {}

    /** /resolve 结果：uin + 各品质（c400/m500/f000） */
    public record ResolveResult(String uin, Map<String, Quality> qualities) {
        public String url(String quality) {
            Quality q = qualities.get(quality);
            return q == null ? null : q.url();
        }
    }

    /**
     * 取歌曲各品质直链。
     *
     * @throws BusinessException sidecar 不可用、未扫码登录或通信失败
     */
    public synchronized ResolveResult resolve(String songMid, String mediaMid) {
        if (!enabled) {
            throw new BusinessException("QQ 音乐浏览器取流未启用（app.qqmusic-sidecar.enabled=false）");
        }
        ensureRunning();
        String body = "{\"songmid\":\"" + songMid + "\",\"media_mid\":\"" + (mediaMid == null ? "" : mediaMid) + "\"}";
        JsonNode node = post("/resolve", body, Duration.ofSeconds(120));
        if (!node.path("ok").asBoolean(false)) {
            String error = node.path("error").asText("");
            if ("not_logged_in".equals(error)) {
                throw new BusinessException("QQ 音乐浏览器登录态已失效，请先完成扫码登录后重试。");
            }
            throw new BusinessException("QQ 音乐取流失败：" + error);
        }
        var qualities = new java.util.HashMap<String, Quality>();
        node.path("qualities").fields().forEachRemaining(e ->
                qualities.put(e.getKey(), new Quality(
                        e.getValue().path("result").asInt(-1),
                        e.getValue().path("url").isNull() ? null : e.getValue().path("url").asText(null))));
        return new ResolveResult(node.path("uin").asText(""), qualities);
    }

    /**
     * 弹出有头浏览器供扫码登录，阻塞等待成功（sidecar 内部最多等 240 秒）。
     */
    public synchronized void login() {
        if (!enabled) {
            throw new BusinessException("QQ 音乐浏览器取流未启用");
        }
        ensureRunning();
        JsonNode node = post("/login", "{}", Duration.ofSeconds(300));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("QQ 音乐扫码登录未完成（超时或被取消），请重试。");
        }
    }

    // ===== 进程管理 =====

    /** 确保 sidecar 存活；未运行则拉起并轮询健康检查。 */
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
                throw new BusinessException("等待 QQ 音乐 sidecar 启动被中断");
            }
            if (health()) {
                log.info("QQ 音乐 sidecar 已就绪 (port={}, profile={})", port, profileDir);
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new BusinessException("QQ 音乐 sidecar 进程启动失败，请检查 Python 环境及 playwright 是否安装（pip install playwright）");
            }
        }
        throw new BusinessException("QQ 音乐 sidecar 启动超时（90 秒）");
    }

    private boolean health() {
        try {
            // 30 秒：sidecar 检测到浏览器崩溃后会冷启动 Chrome 自愈（约 10-25 秒），
            // 超时过短会在自愈窗口内误判 sidecar 死亡并重复拉起进程
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
            // 转发 sidecar 输出到应用日志，避免管道缓冲区写满阻塞子进程
            Thread.ofVirtual().name("qqmusic-sidecar-log").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[qqmusic-sidecar] {}", line);
                    }
                } catch (Exception ignored) {
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (p.isAlive()) {
                    p.destroy();
                }
            }));
            log.info("启动 QQ 音乐 sidecar: {} {} --port {} --profile-dir {}",
                    pythonExe, scriptPath, port, profileDir);
        } catch (Exception e) {
            throw new BusinessException("无法启动 QQ 音乐 sidecar（" + pythonExe + " " + scriptPath + "）："
                    + e.getMessage() + "。请确认已安装 Python 和 playwright（pip install playwright）。");
        }
    }

    private JsonNode post(String path, String json, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = mapper.readTree(resp.body());
            if (resp.statusCode() != 200) {
                throw new BusinessException("QQ 音乐 sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与 QQ 音乐 sidecar 通信失败：" + e.getMessage());
        }
    }

    /**
     * 解析脚本路径：显式配置 &gt; 默认 resources/qqmusic/qqmusic_sidecar.py（兼容工作目录与项目根目录）；
     * 文件系统找不到时回退 classpath（jar 运行解压单文件到临时目录）。
     */
    private static String resolveScript(String configured) {
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            candidates.add(p);
            if (!p.isAbsolute()) {
                candidates.add(Path.of("backend").resolve(p));
            }
        } else {
            candidates.add(Path.of("src", "main", "resources", "qqmusic", "qqmusic_sidecar.py"));
            candidates.add(Path.of("backend", "src", "main", "resources", "qqmusic", "qqmusic_sidecar.py"));
        }
        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath().toString();
            }
        }
        try {
            java.net.URL url = QQMusicBrowserSidecar.class.getClassLoader()
                    .getResource("qqmusic/qqmusic_sidecar.py");
            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    return Path.of(url.toURI()).toAbsolutePath().toString();
                }
                Path out = Path.of(System.getProperty("java.io.tmpdir"),
                        "clipfetch-qqmusic", "qqmusic_sidecar.py");
                Files.createDirectories(out.getParent());
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return out.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("解压 qqmusic_sidecar.py 失败: " + e.getMessage(), e);
        }
        throw new IllegalStateException("找不到 qqmusic_sidecar.py，请检查 app.qqmusic-sidecar.script 配置");
    }
}
