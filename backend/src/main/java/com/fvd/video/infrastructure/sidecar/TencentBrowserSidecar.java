package com.fvd.video.infrastructure.sidecar;

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

/**
 * 腾讯视频浏览器取流 sidecar（Python + Playwright + 系统 Chrome）。
 *
 * <p>腾讯视频播放器通过 POST vd6.l.qq.com/proxyhttp 获取播放信息，请求体中的 cKey
 * 由播放器 JS 动态生成（无法在服务端模拟）。本组件按需拉起常驻 Python 进程
 * （{@code tencent_sidecar.py}），加载真实播放页让播放器自行发起请求，拦截响应
 * 解析出官方 CDN 的 HLS 直链；VIP 内容需在持久化 profile 中扫码登录。
 * 登录态保存在 Chrome 持久化用户目录，过期时调用 {@link #login()} 弹出有头浏览器供扫码。
 */
@Slf4j
@Component
public class TencentBrowserSidecar {

    private final boolean enabled;
    private final String pythonExe;
    private final int port;
    private final String profileDir;
    private final String scriptPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Process process;

    public TencentBrowserSidecar(@Value("${app.tencent-sidecar.enabled:true}") boolean enabled,
                                 @Value("${app.python-exe:python}") String pythonExe,
                                 @Value("${app.tencent-sidecar.port:8097}") int port,
                                 @Value("${app.tencent-sidecar.profile-dir:tencent-profile}") String profileDir,
                                 @Value("${app.tencent-sidecar.script:}") String script) {
        this.enabled = enabled;
        this.pythonExe = pythonExe;
        this.port = port;
        this.profileDir = Path.of(profileDir).toAbsolutePath().toString();
        this.scriptPath = resolveScript(script);
    }

    /**
     * 取视频 HLS 直链。
     *
     * @param url 腾讯视频播放页 URL
     * @return 解析结果（title / duration / formats）
     * @throws BusinessException sidecar 不可用或通信失败
     */
    public synchronized JsonNode resolve(String url) {
        if (!enabled) {
            throw new BusinessException("腾讯视频浏览器取流未启用（app.tencent-sidecar.enabled=false）");
        }
        ensureRunning();
        JsonNode node = post("/resolve", "{\"url\":" + mapper.valueToTree(url) + "}", Duration.ofSeconds(240));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("腾讯视频取流失败：" + node.path("error").asText("未知错误"));
        }
        return node;
    }

    /**
     * 弹出有头浏览器供扫码登录，阻塞等待成功（sidecar 内部最多等 240 秒）。
     */
    public synchronized void login() {
        if (!enabled) {
            throw new BusinessException("腾讯视频浏览器取流未启用");
        }
        ensureRunning();
        JsonNode node = post("/login", "{}", Duration.ofSeconds(300));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("腾讯视频扫码登录未完成（超时或被取消），请重试。");
        }
    }

    /**
     * 导出腾讯视频登录态 cookie 的 Netscape cookies.txt 文本。
     *
     * <p>sidecar Chrome 常驻运行时独占锁定 profile 的 cookie 数据库，yt-dlp
     * {@code --cookies-from-browser} 无法复制该文件（报 Could not copy Chrome cookie
     * database，yt-dlp #7271），故由 sidecar 经 Playwright API 导出。失败返回空串。
     */
    public String cookiesNetscape() {
        if (!enabled) {
            return "";
        }
        try {
            ensureRunning();
            JsonNode node = get("/cookies", Duration.ofSeconds(30));
            return node.path("ok").asBoolean(false) ? node.path("cookies_txt").asText("") : "";
        } catch (Exception e) {
            log.warn("腾讯视频 sidecar cookie 导出失败: {}", e.getMessage());
            return "";
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
                throw new BusinessException("等待腾讯视频 sidecar 启动被中断");
            }
            if (health()) {
                log.info("腾讯视频 sidecar 已就绪 (port={}, profile={})", port, profileDir);
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new BusinessException("腾讯视频 sidecar 进程启动失败，请检查 Python 环境及 playwright 是否安装（pip install playwright）");
            }
        }
        throw new BusinessException("腾讯视频 sidecar 启动超时（90 秒）");
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
            Thread.ofVirtual().name("tencent-sidecar-log").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[tencent-sidecar] {}", line);
                    }
                } catch (Exception ignored) {
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (p.isAlive()) {
                    p.destroy();
                }
            }));
            log.info("启动腾讯视频 sidecar: {} {} --port {} --profile-dir {}",
                    pythonExe, scriptPath, port, profileDir);
        } catch (Exception e) {
            throw new BusinessException("无法启动腾讯视频 sidecar（" + pythonExe + " " + scriptPath + "）："
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
                throw new BusinessException("腾讯视频 sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与腾讯视频 sidecar 通信失败：" + e.getMessage());
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
                throw new BusinessException("腾讯视频 sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与腾讯视频 sidecar 通信失败：" + e.getMessage());
        }
    }

    /**
     * 解析脚本路径：显式配置 &gt; 默认 resources/tencent/tencent_sidecar.py；
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
            candidates.add(Path.of("src", "main", "resources", "tencent", "tencent_sidecar.py"));
            candidates.add(Path.of("backend", "src", "main", "resources", "tencent", "tencent_sidecar.py"));
        }
        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath().toString();
            }
        }
        try {
            java.net.URL url = TencentBrowserSidecar.class.getClassLoader()
                    .getResource("tencent/tencent_sidecar.py");
            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    return Path.of(url.toURI()).toAbsolutePath().toString();
                }
                Path out = Path.of(System.getProperty("java.io.tmpdir"),
                        "clipfetch-tencent", "tencent_sidecar.py");
                Files.createDirectories(out.getParent());
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return out.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("解压 tencent_sidecar.py 失败: " + e.getMessage(), e);
        }
        throw new IllegalStateException("找不到 tencent_sidecar.py，请检查 app.tencent-sidecar.script 配置");
    }
}
