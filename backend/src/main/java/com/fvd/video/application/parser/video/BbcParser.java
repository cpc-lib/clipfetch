package com.fvd.video.application.parser.video;

import com.fvd.video.application.ParseContext;
import com.fvd.video.application.parser.AbstractVideoParser;
import com.fvd.video.application.parser.CookiePolicy;
import com.fvd.video.infrastructure.service.HlsClient;
import com.fvd.video.infrastructure.service.YtDlpService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BBC（bbc.com/news/videos/）视频解析。
 * 流程：页面 __NEXT_DATA__ JSON → playlist.json 获取版本 PID → mediaselector API 获取 m3u8 → HlsClient 解析变体。
 * 下载使用 HlsClient（ffmpeg 原生 HLS 下载），不经过 yt-dlp。
 */
@Slf4j
@Service
public class BbcParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern NEXT_DATA_RE = Pattern.compile(
            "<script[^>]+id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern TITLE_RE = Pattern.compile("<title>([^<]+)</title>");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final int parseTimeout;
    private final HlsClient hlsClient;
    private final YtDlpService ytDlpService;
    private final HttpClient directClient;
    private volatile HttpClient proxyClient;
    /** 解析缓存：页面 URL → formatId → 变体流地址（供下载时按清晰度反查） */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public BbcParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                     HlsClient hlsClient, YtDlpService ytDlpService) {
        this.parseTimeout = parseTimeout;
        this.hlsClient = hlsClient;
        this.ytDlpService = ytDlpService;
        this.directClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Platform platform() {
        return Platform.BBC;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    /** BBC 被墙时走代理的 HttpClient（懒加载） */
    private HttpClient httpClient() {
        if (!ytDlpService.needsProxyFor(Platform.BBC)) {
            return directClient;
        }
        if (proxyClient == null) {
            synchronized (this) {
                if (proxyClient == null) {
                    String proxy = ytDlpService.proxy();
                    URI proxyUri = URI.create(proxy);
                    proxyClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(15))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .proxy(ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort())))
                            .build();
                    log.info("BBC 使用代理: {}", proxy);
                }
            }
        }
        return proxyClient;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String html = fetchPage(url);
        String title = extractTitle(html);

        // 步骤1：从 __NEXT_DATA__ 提取 clip PID
        String clipPid = extractClipPid(html);
        if (clipPid == null) {
            throw new BusinessException("无法从 BBC 页面提取视频信息");
        }
        log.info("BBC 解析: url={}, clipPid={}", url, clipPid);

        // 步骤2：playlist.json 获取版本 PID
        String versionPid = fetchVersionPid(clipPid);
        log.info("BBC 解析: versionPid={}", versionPid);

        // 步骤3：mediaselector API 获取流地址
        String m3u8Url = fetchStreamUrl(versionPid);
        log.info("BBC 解析: m3u8={}", m3u8Url);

        // 解析 m3u8 master playlist 获取清晰度变体
        List<HlsClient.HlsVariant> variants = hlsClient.parseManifest(m3u8Url, null);

        List<FormatInfo> formats = new ArrayList<>();
        Map<String, String> formatMap = new ConcurrentHashMap<>();
        if (variants.isEmpty()) {
            formats.add(new FormatInfo(
                    "bbc_default", "mp4", null, null,
                    null, null, null, null,
                    "默认", false, false, true));
            formatMap.put("bbc_default", m3u8Url);
        } else {
            for (HlsClient.HlsVariant v : variants) {
                String resolution = v.height() > 0 ? v.width() + "x" + v.height() : null;
                String formatId = "bbc_" + (v.height() > 0 ? v.height() : "auto");
                formats.add(new FormatInfo(
                        formatId, "mp4", resolution, v.height() > 0 ? v.height() : null,
                        null, null, null, null,
                        v.label(), false, false, true));
                formatMap.put(formatId, v.uri());
            }
        }

        cache.put(url, formatMap);
        return new VideoInfo(
                clipPid,
                title, null,
                null, null,
                "BBC",
                Platform.BBC.display,
                null, null, formats, null, List.of(), false);
    }

    /**
     * 按清晰度下载：反查变体流地址，用 HlsClient（ffmpeg）下载。
     */
    public void download(String url, String formatId, String title,
                         HttpServletResponse response, String taskId) {
        Map<String, String> formatMap = cache.get(url);
        if (formatMap == null) {
            // 缓存丢失（服务器重启后），重新解析填充缓存
            parse(new ParseContext(url, null, null));
            formatMap = cache.get(url);
        }
        String streamUrl = formatMap != null ? formatMap.get(formatId) : null;
        if (streamUrl == null) {
            streamUrl = formatMap != null ? formatMap.values().stream().findFirst().orElse(null) : null;
        }
        if (streamUrl == null) {
            throw new BusinessException("请先解析视频后再下载");
        }
        hlsClient.downloadToResponse(streamUrl,
                title != null ? title : "bbc-video", null, response, taskId);
    }

    private String fetchPage(String pageUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(pageUrl))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .GET().build();
            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("BBC 页面返回状态码 " + resp.statusCode());
            }
            return resp.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取 BBC 页面失败：" + e.getMessage());
        }
    }

    /** 从 __NEXT_DATA__ JSON 提取 clip PID（从 contents 中 type=video 的 model.locator 提取） */
    private String extractClipPid(String html) {
        Matcher m = NEXT_DATA_RE.matcher(html);
        if (!m.find()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(m.group(1));
            JsonNode page = root.path("props").path("pageProps").path("page");
            for (var entry : page.properties()) {
                JsonNode node = entry.getValue();
                for (JsonNode content : node.path("contents")) {
                    if (!"video".equals(content.path("type").asText())) {
                        continue;
                    }
                    String locator = content.path("model").path("locator").asText(null);
                    if (locator != null && locator.contains("pid:")) {
                        // urn:bbc:pips:pid:p0pcw7m7 → p0pcw7m7
                        return locator.substring(locator.lastIndexOf("pid:") + 4);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("解析 __NEXT_DATA__ 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从 playlist.json 获取默认版本 PID */
    private String fetchVersionPid(String clipPid) {
        try {
            String url = "https://www.bbc.co.uk/programmes/" + clipPid + "/playlist.json";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("BBC playlist.json 返回状态码 " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String pid = root.path("defaultAvailableVersion").path("pid").asText(null);
            if (pid == null || pid.isBlank()) {
                throw new BusinessException("BBC playlist.json 中未找到版本 PID");
            }
            return pid;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取 BBC playlist 失败：" + e.getMessage());
        }
    }

    /** 从 mediaselector API 获取 HLS m3u8 地址 */
    private String fetchStreamUrl(String versionPid) {
        // 先试 pc mediaset，失败则回退 iptv-all
        for (String mediaset : List.of("pc", "iptv-all")) {
            try {
                String url = "https://open.live.bbc.co.uk/mediaselector/6/select/version/2.0/mediaset/"
                        + mediaset + "/vpid/" + versionPid + "/format/json";
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", UA)
                        .GET().build();
                HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) {
                    continue;
                }
                JsonNode root = mapper.readTree(resp.body());
                // 遍历 media 数组，找 video 类型的 HLS 连接（transferFormat 在 connection 级别）
                for (JsonNode media : root.path("media")) {
                    if (!"video".equals(media.path("kind").asText())) {
                        continue;
                    }
                    for (JsonNode conn : media.path("connection")) {
                        if (!"hls".equals(conn.path("transferFormat").asText())) {
                            continue;
                        }
                        String href = conn.path("href").asText(null);
                        if (href != null && href.contains(".m3u8")) {
                            // 优先 HTTPS
                            if ("https".equals(conn.path("protocol").asText())) {
                                return href;
                            }
                        }
                    }
                    // 没找到 HTTPS 时回退 HTTP
                    for (JsonNode conn : media.path("connection")) {
                        if (!"hls".equals(conn.path("transferFormat").asText())) {
                            continue;
                        }
                        String href = conn.path("href").asText(null);
                        if (href != null && href.contains(".m3u8")) {
                            return href;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("BBC mediaselector {} 失败: {}", mediaset, e.getMessage());
            }
        }
        throw new BusinessException("无法获取 BBC 视频流地址");
    }

    private String extractTitle(String html) {
        Matcher m = TITLE_RE.matcher(html);
        if (m.find()) {
            String t = m.group(1).trim();
            if (t.endsWith(" - BBC News")) {
                t = t.substring(0, t.length() - 11).trim();
            }
            return t;
        }
        return "BBC 视频";
    }
}
