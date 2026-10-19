package cc.ivera.magnet.client;

import cc.ivera.magnet.domain.DefaultTrackers;
import cc.ivera.shared.web.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
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
 * CookieManager 自动管理 SID 会话；请求返回 403 时自动重新登录并重试一次，
 * 且并发命中时只真正登录一次（冷却窗口内其他线程复用新会话），避免频繁登录冲击 qBittorrent。
 * 已验证细节：登录返回 204 视为本地回环免认证成功；webapiVersion 返回纯文本；
 * 5.x torrents/stop 与 4.x torrents/pause 兼容（404 回退）。
 */
@Slf4j
@Component
public class QBittorrentClient {

    /** 重登冷却窗口（毫秒）：窗口内其他线程不再重复登录，直接复用新会话重试 */
    private static final long LOGIN_COOLDOWN_MS = 5000;

    private final ObjectMapper objectMapper;
    private final CookieManager cookies = new CookieManager();

    /** 重登串行化锁：会话集中失效时只允许一个线程真正登录，避免并发重登冲击 qBittorrent */
    private final Object loginLock = new Object();

    /** 最近一次登录成功时间戳（毫秒），用于重登冷却去重 */
    private volatile long lastLoginAt;

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
        String enrichedUri = DefaultTrackers.enrichMagnet(magnetUri);
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"urls", enrichedUri});
        String effectiveSavePath = (customSavePath != null && !customSavePath.isBlank())
                ? customSavePath
                : savePath;
        if (effectiveSavePath != null && !effectiveSavePath.isBlank()) {
            fields.add(new String[]{"savepath", effectiveSavePath});
        }
        // 必须处于启动态才能连接 peer 获取元数据；获取完成后会立即暂停
        fields.add(new String[]{"paused", "false"});
        fields.add(new String[]{"stopped", "false"});
        fields.add(new String[]{"tags", "clipfetch-parse"});
        fields.add(new String[]{"autoTMM", "false"});
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

    /** qBittorrent 5.x properties.has_metadata 标识是否已获取全部种子元数据。 */
    public boolean hasMetadata(String hash) {
        if (!torrentExists(hash)) return false;
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/torrents/properties?hash=" + encode(hash)));
        if (resp.statusCode() != 200) {
            throw new BusinessException("查询元数据状态失败：HTTP " + resp.statusCode());
        }
        return parseObject(resp).path("has_metadata").asBoolean(false);
    }

    /** 导出实际 .torrent 元数据（不是文件名清单），用于以后恢复同一个 Info Hash。 */
    public byte[] exportTorrent(String hash) {
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest.Builder builder = get("/api/v2/torrents/export?hash=" + encode(hash));
        try {
            HttpResponse<byte[]> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 403) {
                ensureLogin();
                resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            }
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
                throw new BusinessException("无法导出种子元数据（HTTP " + resp.statusCode()
                        + "），请检查 qBittorrent 是否已获取完整元数据");
            }
            if (resp.body().length > 8 * 1024 * 1024) {
                throw new BusinessException("种子元数据过大（超过 8MB），拒绝缓存");
            }
            return resp.body();
        } catch (IOException e) {
            throw new BusinessException("获取 .torrent 元数据失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("获取 .torrent 元数据被中断");
        }
    }

    /** 使用已缓存的 .torrent 二进制重新建种，避免重复依赖 Peer 获取元数据。 */
    public void addTorrentFile(byte[] torrentData, String destination) {
        if (torrentData == null || torrentData.length == 0 || torrentData.length > 8 * 1024 * 1024) {
            throw new BusinessException("缓存的 .torrent 元数据无效");
        }
        String boundary = "----clipfetch" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String[] part : List.of(
                new String[]{"savepath", destination},
                new String[]{"paused", "true"},
                new String[]{"stopped", "true"},
                new String[]{"autoTMM", "false"},
                new String[]{"tags", "clipfetch-parse"}
        )) {
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + part[0] + "\"\r\n\r\n" + part[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"torrents\"; filename=\"clipfetch.torrent\""
                + "\r\nContent-Type: application/x-bittorrent\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(torrentData);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(host + "/api/v2/torrents/add"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        HttpResponse<String> response = sendWithAuth(request);
        if (response.statusCode() != 200 || (response.body() != null && response.body().contains("Fails"))) {
            throw new BusinessException("通过缓存恢复种子失败（HTTP " + response.statusCode() + "）");
        }
    }

    /** 只接管本应用通过解析接口创建并打上标记的种子。 */
    public boolean isClipfetchParseTorrent(String hash) {
        String tags = torrentInfo(hash).path("tags").asText("");
        for (String tag : tags.split(",")) {
            if ("clipfetch-parse".equals(tag.trim())) return true;
        }
        return false;
    }

    public void markManaged(String hash) {
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/addTags",
                List.of(new String[]{"hashes", hash}, new String[]{"tags", "clipfetch-download"})));
        if (resp.statusCode() != 200) throw new BusinessException("标记下载种子失败：HTTP " + resp.statusCode());
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

    /** 指定种子的信息列表（下载管理进度轮询）：只查当前用户持有的种子，不拉取共享实例中其他任务的完整列表 */
    public List<JsonNode> listTorrents(List<String> hashes) {
        requireHost();
        if (hashes == null || hashes.isEmpty()) return new ArrayList<>();
        // qBittorrent 用 | 分隔多个 Hash，但 | 在 URL query 中非法，必须百分号编码
        String filter = URLEncoder.encode(String.join("|", hashes), StandardCharsets.UTF_8);
        HttpResponse<String> resp = sendWithAuth(get("/api/v2/torrents/info?hashes=" + filter));
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

    /** 移动保存目录可能是异步操作，必须确认实际目录才允许返回成功。 */
    public void waitForSavePath(String hash, String expectedPath) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            String actual = torrentInfo(hash).path("save_path").asText("");
            if (expectedPath.replace('\\', '/').replaceAll("/+$", "")
                    .equalsIgnoreCase(actual.replace('\\', '/').replaceAll("/+$", ""))) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("等待保存路径变更被中断");
            }
        }
        throw new BusinessException("qBittorrent 未确认保存目录变更，请检查目录权限");
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
     * 为当前种子设置下载完成后停止做种，不修改 qBittorrent 的全局分享配置。
     * Web API 2.15.1 要求 ratioLimit、seedingTimeLimit、
     * inactiveSeedingTimeLimit、shareLimitAction 全部提供；
     * shareLimitsMode 在后续版本需要，旧版本会忽略该附加字段。
     */
    public void disableSeeding(String hash) {
        List<String[]> ratioFields = List.of(
                new String[]{"hashes", hash},
                new String[]{"ratioLimit", "0.0"},
                new String[]{"seedingTimeLimit", "-1"},
                new String[]{"inactiveSeedingTimeLimit", "-1"},
                new String[]{"shareLimitAction", "Stop"},
                new String[]{"shareLimitsMode", "MatchAny"}
        );
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/setShareLimits", ratioFields));
        if (resp.statusCode() != 200) {
            String detail = resp.body() == null ? "" : resp.body().trim();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            log.warn("qBittorrent 设置分享率限制失败 hash={} status={} detail={}", hash, resp.statusCode(), detail);
            throw new BusinessException("设置分享率限制失败：HTTP " + resp.statusCode()
                    + (detail.isBlank() ? "" : " (" + detail + ")"));
        }
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

    /**
     * 为指定种子追加 Tracker 地址列表（加速 Peer 发现并大幅提升下载速度）。
     */
    public void addTrackers(String hash, List<String> trackerUrls) {
        requireHost();
        if (hash == null || hash.isBlank() || trackerUrls == null || trackerUrls.isEmpty()) {
            return;
        }
        String urls = String.join("\n", trackerUrls);
        List<String[]> fields = List.of(
                new String[]{"hash", hash},
                new String[]{"urls", urls}
        );
        HttpResponse<String> resp = sendWithAuth(postForm("/api/v2/torrents/addTrackers", fields));
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            log.warn("qBittorrent 追加 Tracker 响应非预期状态 hash={} status={} body={}", hash, resp.statusCode(), resp.body());
        }
    }

    /** 从 .torrent 二进制导入后，只等待 qBittorrent 完成内存注册，不依赖 Peer。 */
    public void waitImportedMetadata(String hash) {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (torrentExists(hash) && hasMetadata(hash)) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("等待种子恢复被中断");
            }
        }
        throw new BusinessException("导入缓存后 qBittorrent 仍未恢复元数据，请检查导入日志");
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
                ensureLogin();
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
     * 会话失效（403）时登录。并发命中时只允许一个线程真正登录，其余线程在冷却窗口内
     * 直接复用新会话重试，避免短时间内重复登录冲击 qBittorrent（可能触发登录保护封禁）。
     */
    private void ensureLogin() {
        synchronized (loginLock) {
            if (System.currentTimeMillis() - lastLoginAt < LOGIN_COOLDOWN_MS) {
                return;
            }
            log.info("qBittorrent 会话失效，重新登录 Web API");
            login();
            lastLoginAt = System.currentTimeMillis();
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
