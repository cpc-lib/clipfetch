package cc.ivera.parser.infrastructure.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cc.ivera.shared.web.BusinessException;
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
 * SpankBang 浏览器取流 sidecar（Python + Playwright + 系统 Chrome）。
 *
 * <p>SpankBang 的 Cloudflare 防护为 Bot Management 级别，cf_clearance 绑定真实
 * Chrome 的 TLS 指纹，yt-dlp/curl 带合法 cookies 仍被 403。本组件按需拉起常驻
 * Python 进程（{@code spankbang_sidecar.py}），用真实 Chrome 加载视频页让 CF
 * 挑战自动通过，读取页面内嵌 stream_data 全局变量（各档 CDN 直链）；
 * CDN 本身无 CF 挑战，yt-dlp 直接下载。
 */
@Slf4j
@Component
public class SpankBangBrowserSidecar {

    private final boolean enabled;
    private final String pythonExe;
    private final int port;
    private final String profileDir;
    private final String proxy;
    private final String scriptPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Process process;

    public SpankBangBrowserSidecar(@Value("${app.spankbang-sidecar.enabled:true}") boolean enabled,
                                   @Value("${app.python-exe:python}") String pythonExe,
                                   @Value("${app.spankbang-sidecar.port:8098}") int port,
                                   @Value("${app.spankbang-sidecar.profile-dir:spankbang-profile}") String profileDir,
                                   @Value("${app.spankbang-sidecar.script:}") String script,
                                   @Value("${app.proxy:}") String proxy) {
        this.enabled = enabled;
        this.pythonExe = pythonExe;
        this.port = port;
        this.profileDir = Path.of(profileDir).toAbsolutePath().toString();
        this.proxy = proxy;
        this.scriptPath = resolveScript(script);
    }

    /**
     * 解析视频页为各档 CDN 直链。
     *
     * @param url SpankBang 视频页 URL
     * @return 解析结果（title / duration / thumbnail / formats）
     * @throws BusinessException sidecar 不可用或解析失败
     */
    public synchronized JsonNode resolve(String url) {
        if (!enabled) {
            throw new BusinessException("SpankBang 浏览器取流未启用（app.spankbang-sidecar.enabled=false）");
        }
        ensureRunning();
        JsonNode node = post("/resolve", "{\"url\":" + mapper.valueToTree(url) + "}", Duration.ofSeconds(120));
        if (!node.path("ok").asBoolean(false)) {
            throw new BusinessException("SpankBang 取流失败：" + node.path("error").asText("未知错误"));
        }
        return node;
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
                throw new BusinessException("等待 SpankBang sidecar 启动被中断");
            }
            if (health()) {
                log.info("SpankBang sidecar 已就绪 (port={}, profile={})", port, profileDir);
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new BusinessException("SpankBang sidecar 进程启动失败，请检查 Python 环境及 playwright 是否安装（pip install playwright）");
            }
        }
        throw new BusinessException("SpankBang sidecar 启动超时（90 秒）");
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
            List<String> cmd = new ArrayList<>(List.of(
                    pythonExe, scriptPath,
                    "--port", String.valueOf(port),
                    "--profile-dir", profileDir));
            if (proxy != null && !proxy.isBlank()) {
                cmd.add("--proxy");
                cmd.add(proxy);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            Process p = process;
            Thread.ofVirtual().name("spankbang-sidecar-log").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[spankbang-sidecar] {}", line);
                    }
                } catch (Exception ignored) {
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (p.isAlive()) {
                    p.destroy();
                }
            }));
            log.info("启动 SpankBang sidecar: {}", String.join(" ", cmd));
        } catch (Exception e) {
            throw new BusinessException("无法启动 SpankBang sidecar（" + pythonExe + " " + scriptPath + "）："
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
                throw new BusinessException("SpankBang sidecar 返回 " + resp.statusCode() + "："
                        + node.path("error").asText("未知错误"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("与 SpankBang sidecar 通信失败：" + e.getMessage());
        }
    }

    /**
     * 解析脚本路径：显式配置 &gt; 默认 resources/spankbang/spankbang_sidecar.py；
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
            candidates.add(Path.of("src", "main", "resources", "spankbang", "spankbang_sidecar.py"));
            candidates.add(Path.of("backend", "src", "main", "resources", "spankbang", "spankbang_sidecar.py"));
        }
        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath().toString();
            }
        }
        try {
            java.net.URL url = SpankBangBrowserSidecar.class.getClassLoader()
                    .getResource("spankbang/spankbang_sidecar.py");
            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    return Path.of(url.toURI()).toAbsolutePath().toString();
                }
                Path out = Path.of(System.getProperty("java.io.tmpdir"),
                        "clipfetch-spankbang", "spankbang_sidecar.py");
                Files.createDirectories(out.getParent());
                try (java.io.InputStream in = url.openStream()) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return out.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("解压 spankbang_sidecar.py 失败: " + e.getMessage(), e);
        }
        throw new IllegalStateException("找不到 spankbang_sidecar.py，请检查 app.spankbang-sidecar.script 配置");
    }
}
