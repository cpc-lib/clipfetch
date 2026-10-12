package cc.ivera.magnet.client;

import cc.ivera.shared.web.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * qBittorrent Web API 客户端（纯 JDK HttpClient，参照 SubtitleGateway 范式）。
 * CookieManager 自动管理 SID 会话；请求返回 403 时自动重新登录并重试一次。
 * 已验证细节：登录返回 204 视为本地回环免认证成功；webapiVersion 返回纯文本；
 * 5.x torrents/stop 与 4.x torrents/pause 兼容（404 回退）。
 */
@Slf4j
@Component
public class QBittorrentClient {

    private final ObjectMapper objectMapper;
    private final CookieManager cookies = new CookieManager();

    @Value("${app.magnet.qbt-host:}")
    private String host;

    @Value("${app.magnet.qbt-user:admin}")
    private String user;

    @Value("${app.magnet.qbt-pass:}")
    private String pass;

    /** 保存路径：必须是 qBittorrent 进程可见的路径；留空则使用 qBittorrent 默认保存目录 */
    @Value("${app.magnet.qbt-savepath:}")
    private String savePath;

    /** qBittorrent 保存路径在宿主机上的对应目录（后端从此目录读取文件供浏览器下载） */
    @Value("${app.magnet.qbt-downloads-host-dir:}")
    private String downloadsHostDir;

    /** 元数据获取超时（秒） */
    @Value("${app.magnet.metadata-timeout:60}")
    private long metadataTimeoutSec;

    public QBittorrentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String getSavePath() {
        return savePath;
    }

    public String getDownloadsHostDir() {
        return downloadsHostDir;
    }

    /** 引擎健康检查：返回 Web API 版本号（纯文本） */
    public String version() {
        requireHost();
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/app/webapiVersion"));
        if (resp.statusCode() != 200) {
            throw new BusinessException("qBittorrent 连接失败：HTTP " + resp.statusCode());
        }
        return resp.body().trim();
    }

    public void addTorrent(String magnetUri) {
        addTorrent(magnetUri, null);
    }

    /**
     * 添加磁力种子（启动态：获取元数据需要连接 peer，必须启动）。
     * 兼容 4.x 纯文本 "Ok." 与 5.x JSON 响应；响应含 Fails 视为添加失败。
     * @param customSavePath 自定义保存路径（qBittorrent 容器内路径，如 /downloads/uuid）
     */
    public void addTorrent(String magnetUri, String customSavePath) {
        requireHost();
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"urls", magnetUri});
        String effectiveSavePath = (customSavePath != null && !customSavePath.isBlank())
                ? customSavePath
                : savePath;
        if (effectiveSavePath != null && !effectiveSavePath.isBlank()) {
            fields.add(new String[]{"savepath", effectiveSavePath});
        }
        // 必须处于启动态才能连接 peer 获取元数据；获取完成后会立即暂停
        fields.add(new String[]{"paused", "false"});
        fields.add(new String[]{"stopped", "false"});
        HttpResponse<String> resp = sendWithAuth(postMultipart("/api/v2/torrents/add", fields));
        if (resp.statusCode() != 200) {
            throw new BusinessException("添加种子失败：HTTP " + resp.statusCode() + " " + resp.body());
        }
        String body = resp.body() == null ? "" : resp.body();
        if (body.contains("Fails")) {
            throw new BusinessException("添加种子失败：" + body);
        }
    }

    /** 种子是否存在 */
    public boolean torrentExists(String hash) {
        requireHost();
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/torrents/info?hashes=" + hash));
        if (resp.statusCode() != 200) {
            throw new BusinessException("查询种子失败：HTTP " + resp.statusCode());
        }
        return parseArray(resp).size() > 0;
    }

    /** 是否已取得元数据 */
    public boolean hasMetadata(String hash) {
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/torrents/properties?hash=" + hash));
        if (resp.statusCode() != 200) {
            return false;
        }
        JsonNode node = parseObject(resp);
        return node.path("has_metadata").asBoolean(false);
    }

    /** 单个种子信息（torrents/info 首条）：含 name/size/save_path/state/progress 等 */
    public JsonNode torrentInfo(String hash) {
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/torrents/info?hashes=" + hash));
        if (resp.statusCode() != 200) {
            throw new BusinessException("查询种子信息失败：HTTP " + resp.statusCode());
        }
        List<JsonNode> list = parseArray(resp);
        if (list.isEmpty()) {
            throw new BusinessException("qBittorrent 中未找到该种子");
        }
        return list.get(0);
    }

    /** 全部种子列表（下载管理进度轮询） */
    public List<JsonNode> listTorrents() {
        requireHost();
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/torrents/info"));
        if (resp.statusCode() != 200) {
            throw new BusinessException("查询下载任务失败：HTTP " + resp.statusCode());
        }
        return parseArray(resp);
    }

    /** 文件清单：[{index, name, size, progress, priority}] */
    public List<JsonNode> torrentFiles(String hash) {
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/torrents/files?hash=" + hash));
        if (resp.statusCode() != 200) {
            throw new BusinessException("获取文件列表失败：HTTP " + resp.statusCode());
        }
        return parseArray(resp);
    }

    /** 设置文件优先级：0=跳过 1=正常 */
    public void setFilePriority(String hash, List<Integer> fileIndexes, int priority) {
        if (fileIndexes.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Integer i : fileIndexes) {
            ids.add(String.valueOf(i));
        }
        List<String[]> fields = List.of(
                new String[]{"hash", hash},
                new String[]{"id", String.join("|", ids)},
                new String[]{"priority", String.valueOf(priority)}
        );
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/filePrio", fields));
        if (resp.statusCode() != 200) {
            throw new BusinessException("设置文件优先级失败：HTTP " + resp.statusCode() + " " + resp.body());
        }
    }

    /** 更新种子保存路径（qBittorrent 容器内路径） */
    public void setSavePath(String hash, String newPath) {
        // qBittorrent 5.x 用 hashes，4.x 用 hash；都传，多余参数被忽略
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"hashes", hash});
        fields.add(new String[]{"hash", hash});
        fields.add(new String[]{"location", newPath});
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/setLocation", fields));
        if (resp.statusCode() != 200) {
            throw new BusinessException("更新保存路径失败：HTTP " + resp.statusCode() + " " + resp.body());
        }
    }

    /** 暂停种子：5.x torrents/stop，404 时回退 4.x torrents/pause */
    public void stopTorrent(String hash) {
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/stop", field("hashes", hash)));
        if (resp.statusCode() == 404) {
            resp = sendWithAuth(postForm("/api/v2/torrents/pause", field("hashes", hash)));
        }
        if (resp.statusCode() != 200) {
            throw new BusinessException("暂停种子失败：HTTP " + resp.statusCode());
        }
    }

    /** 开始/继续种子：5.x torrents/start，404 时回退 4.x torrents/resume */
    public void startTorrent(String hash) {
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/start", field("hashes", hash)));
        if (resp.statusCode() == 404) {
            resp = sendWithAuth(postForm("/api/v2/torrents/resume", field("hashes", hash)));
        }
        if (resp.statusCode() != 200) {
            throw new BusinessException("开始种子失败：HTTP " + resp.statusCode());
        }
    }

    /**
     * 设置种子下载完成后不做种：分享率限制 0 + 上传速度限制 1 KB/s（双保险）。
     */
    public void disableSeeding(String hash) {
        // 分享率 0：下载完成即达到分享率上限，自动停止做种
        List<String[]> ratioFields = List.of(
                new String[]{"hashes", hash},
                new String[]{"ratioLimit", "0"}
        );
        sendWithAuth(postForm("/api/v2/torrents/setShareLimits", ratioFields));
        // 上传速度限制 1 B/s：几乎不上传
        List<String[]> uploadFields = List.of(
                new String[]{"hashes", hash},
                new String[]{"limit", "1"}
        );
        sendWithAuth(postForm("/api/v2/torrents/setUploadLimit", uploadFields));
    }

    /** 删除种子任务，deleteFiles=true 时同时删除已下载文件 */
    public void deleteTorrent(String hash, boolean deleteFiles) {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"hashes", hash});
        fields.add(new String[]{"deleteFiles", String.valueOf(deleteFiles)});
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/delete", fields));
        if (resp.statusCode() != 200) {
            throw new BusinessException("删除任务失败：HTTP " + resp.statusCode());
        }
    }

    /** 等待元数据：每秒轮询一次，超时抛出异常 */
    public void waitMetadata(String hash) throws InterruptedException {
        long deadline = System.currentTimeMillis() + metadataTimeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (hasMetadata(hash)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new BusinessException("获取元数据超时（" + metadataTimeoutSec + " 秒），请检查网络与可用 Peer 后重试");
    }

    // ==================== 内部工具 ====================

    private void requireHost() {
        if (host == null || host.isBlank()) {
            throw new BusinessException("尚未配置 qBittorrent 连接，请在 backend/.env 中配置 qbt-host / qbt-user / qbt-pass");
        }
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(host + path))
                .timeout(Duration.ofSeconds(15))
                .GET();
    }

    private HttpRequest.Builder postForm(String path, List<String[]> fields) {
        StringBuilder sb = new StringBuilder();
        for (String[] f : fields) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(f[0])).append('=').append(encode(f[1]));
        }
        return HttpRequest.newBuilder(URI.create(host + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()));
    }

    private HttpRequest.Builder postMultipart(String path, List<String[]> fields) {
        String boundary = "----clipfetch" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        for (String[] f : fields) {
            sb.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n")
                    .append(f[1]).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        return HttpRequest.newBuilder(URI.create(host + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()));
    }

    /**
     * 发送请求；403（会话失效）时重新登录并重试一次。
     * 每次请求使用独立 HttpClient（不共享连接池），避免 qBittorrent 提前关闭连接导致
     * "HTTP/1.1 header parser received no bytes"。
     */
    private HttpResponse<String> sendWithAuth(HttpRequest.Builder builder) {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        try {
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 403) {
                login();
                resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            }
            return resp;
        } catch (IOException e) {
            log.warn("qBittorrent 请求失败: {}", e.toString());
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BusinessException("qBittorrent 连接失败（" + detail + "），请确认 qBittorrent 已启动且 Web UI 已开启");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("qBittorrent 请求被中断");
        }
    }

    /**
     * 登录 Web API：200 + "Ok." 或 204（本地回环免认证）均视为成功。
     * 使用独立 HttpClient，不共享连接池。
     */
    private void login() {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        try {
            String body = "username=" + encode(user) + "&password=" + encode(pass == null ? "" : pass);
            HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/api/v2/auth/login"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = (resp.statusCode() == 200 && "Ok.".equals(resp.body().trim()))
                    || resp.statusCode() == 204;
            if (!ok) {
                throw new BusinessException("qBittorrent 登录失败（HTTP " + resp.statusCode()
                        + "），请检查 backend/.env 中的 qbt-user / qbt-pass");
            }
        } catch (IOException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BusinessException("qBittorrent 连接失败（" + detail + "），请确认 qBittorrent 已启动且 Web UI 已开启");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("qBittorrent 登录被中断");
        }
    }

    private List<JsonNode> parseArray(HttpResponse<String> resp) {
        try {
            JsonNode node = objectMapper.readTree(resp.body());
            List<JsonNode> list = new ArrayList<>();
            node.forEach(list::add);
            return list;
        } catch (Exception e) {
            throw new BusinessException("解析 qBittorrent 响应失败：" + e.getMessage());
        }
    }

    private JsonNode parseObject(HttpResponse<String> resp) {
        try {
            return objectMapper.readTree(resp.body());
        } catch (Exception e) {
            throw new BusinessException("解析 qBittorrent 响应失败：" + e.getMessage());
        }
    }

    private String encode(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    /** 构造单字段表单参数列表（避免 List.of 与数组 varargs 的歧义） */
    private List<String[]> field(String key, String value) {
        List<String[]> list = new ArrayList<>();
        list.add(new String[]{key, value});
        return list;
    }
}
