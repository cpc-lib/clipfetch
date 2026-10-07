package com.fvd.video.infrastructure;

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
 * 酷狗音乐浏览器取流 sidecar（Python + Playwright + 系统 Chrome）。
 *
 * <p>酷狗 PC 播放接口 play/songinfo 需要页面运行时 H5 签名（window.infSign），
 * 且匿名一律返回 err_code=30020，纯 HTTP 无法复刻。本组件按需拉起常驻 Python 进程
 * （{@code kugou_sidecar.py}），通过 127.0.0.1 HTTP 在真实页面上下文里取直链；
 * 登录态保存在 Chrome 持久化用户目录，过期时调用 {@link #login()} 弹出有头浏览器供扫码。
 */
@Slf4j
@Component
public class KugouMusicBrowserSidecar {

    private final boolean enabled;
    private final String pythonExe;
    private final int port;
    private final String profileDir;
    private final String scriptPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Process process;

    public KugouMusicBrowserSidecar(@Value("${app.kugou-sidecar.enabled:true}") boolean enabled,
                                    @Value("${app.python-exe:python}") String pythonExe,
                                    @Value("${app.kugou-sidecar.port:8096}") int port,
                                    @Value("${app.kugou-sidecar.profile-dir:kugou-profile}") String profileDir,
                                    @Value("${app.kugou-sidecar.script:}") String script) {
        this.enabled = enabled;
        this.pythonExe = pythonExe;
        this.port = port;
        this.profileDir = Path.of(profileDir).toAbsolutePath().toString();
        this.scriptPath = resolveScript(script);
    }

    /**
     * 取歌曲播放信息（err_code/status/play_url 等原样透传在返回 JSON 的 data 中）。
     *
     * @throws BusinessException sidecar 不可用或通信失败
     */
    public synchronized JsonNode resolve(String url) {
        if (!enabled) {
            throw new BusinessException("酷狗浏览器取流未启用（app.kugou-sidecar.enabled=false）");
        }
        ensureRunning();
        JsonNode node = post("/resolve", "{\"url\":" + mapper.valueToTree(url) + "}", Duration.ofSeconds(120));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("酷狗取流失败：" + node.path("error").asText("未知错误"));
        }
        return node;
    }

    /**
     * 弹出有头浏览器供扫码登录，阻塞等待成功（sidecar 内部最多等 240 秒）。
     */
    public synchronized void login() {
        if (!enabled) {
            throw new BusinessException("酷狗浏览器取流未启用");
        }
        ensureRunning();
        JsonNode node = post("/login", "{}", Duration.ofSeconds(300));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("酷狗扫码登录未完成（超时或被取消），请重试。");
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
                throw new BusinessException("等待酷狗 sidecar 启动被中断");
            }
            if (health()) {
                log.info("酷狗 sidecar 已就绪 (port={}, profile={})", port, profileDir);
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new BusinessException("酷狗 sidecar 进程启动失败，请检查 Python 环境及 playwright 是否安装（pip install playwright）");
            }
        }
        throw new BusinessException("酷狗 sidecar 启动超时（90 秒）");
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
            Thread.ofVirtual().name("kugou-sidecar-log").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[kugou-sidecar] {}", line);
                    }
                } catch (Exception ignored) {
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (p.isAlive()) {
                    p.destroy();
                }
            }));
            log.info("启动酷狗 sidecar: {} {} --port {} --profile-dir {}",
                    pythonExe, scriptPath, port, profileDir);
        } catch (Exception e) {
            throw new BusinessException("无法启动酷狗 sidecar（" + pythonExe + " " + scriptPath + "）："
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
                throw new BusinessException("酷狗 sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与酷狗 sidecar 通信失败：" + e.getMessage());
        }
    }

    /**
     * 解析脚本路径：显式配置 &gt; 默认 resources/kugou/kugou_sidecar.py；
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
            candidates.add(Path.of("src", "main", "resources", "kugou", "kugou_sidecar.py"));
            candidates.add(Path.of("backend", "src", "main", "resources", "kugou", "kugou_sidecar.py"));
        }
        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath().toString();
            }
        }
        try {
            java.net.URL url = KugouMusicBrowserSidecar.class.getClassLoader()
                    .getResource("kugou/kugou_sidecar.py");
            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    return Path.of(url.toURI()).toAbsolutePath().toString();
                }
                Path out = Path.of(System.getProperty("java.io.tmpdir"),
                        "clipfetch-kugou", "kugou_sidecar.py");
                Files.createDirectories(out.getParent());
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return out.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("解压 kugou_sidecar.py 失败: " + e.getMessage(), e);
        }
        throw new IllegalStateException("找不到 kugou_sidecar.py，请检查 app.kugou-sidecar.script 配置");
    }
}
