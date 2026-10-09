package com.fvd.parser.application.parser.video;

import com.fvd.parser.application.ParseContext;
import com.fvd.parser.application.parser.AbstractVideoParser;
import com.fvd.parser.application.parser.CookiePolicy;
import com.fvd.parser.infrastructure.service.HlsClient;
import com.fvd.parser.infrastructure.service.YtDlpService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.parser.domain.FormatInfo;
import com.fvd.parser.domain.Platform;
import com.fvd.parser.domain.VideoInfo;
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

/**
 * Amasian TV（amasian.tv）视频解析。
 *
 * <pre>
 * 页面 URL（/video/series/{series}/{episode} 或 /video/movie/{movie}）
 *     ↓ 取末段作为 content slug
 * GET odkmedia.io/odx/api/v4/content/{slug}/  → id / title / duration / thumbnail
 *     ↓
 * GET odkmedia.io/odx/api/v4/playback/{id}/   → manifests.stream_url（HLS master m3u8）
 *     ↓
 * HlsClient 解析 master playlist → 多清晰度变体（1080p/720p/480p/360p/240p/180p）
 *     ↓
 * 下载复用 HlsClient（ffmpeg 原生 HLS 下载）
 * </pre>
 * Amasian TV 仅限北美/南美访问，国内直连不通时解析走 HTTP 代理。
 */
@Slf4j
@Service
public class AmasianTvParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String REFERER = "https://amasian.tv/";
    private static final String API_BASE = "https://odkmedia.io/odx/api/v4";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final int parseTimeout;
    private final HlsClient hlsClient;
    private final YtDlpService ytDlpService;
    private final HttpClient directClient;
    private volatile HttpClient proxyClient;
    /** 解析缓存：页面 URL → formatId → 变体流地址（供下载时按清晰度反查） */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    /** 字幕缓存：页面 URL → 字幕轨道列表（HLS master playlist 的 #EXT-X-MEDIA:TYPE=SUBTITLES） */
    private final Map<String, List<HlsClient.SubtitleTrack>> subtitleCache = new ConcurrentHashMap<>();
    /** master playlist URL 缓存：页面 URL → HLS master m3u8 地址（供 yt-dlp 下载字幕时使用） */
    private final Map<String, String> masterUrlCache = new ConcurrentHashMap<>();

    public AmasianTvParser(@Value("${app.parse-timeout:60}") int parseTimeout,
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
        return Platform.AMASIAN_TV;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    /** Amasian TV 被墙时走代理的 HttpClient（懒加载） */
    private HttpClient httpClient() {
        if (!ytDlpService.needsProxyFor(Platform.AMASIAN_TV)) {
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
                    log.info("Amasian TV 使用代理: {}", proxy);
                }
            }
        }
        return proxyClient;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        // 可达性判断（结果缓存）：
        //  - 美洲网络直连可达 → 直连
        //  - 不可达但已配置 PROXY_URL → httpClient() 自动走代理
        //  - 不可达且未配置代理 → 直接给出明确提示，避免无意义的 404 请求
        if (!ytDlpService.isReachable(Platform.AMASIAN_TV)
                && !ytDlpService.needsProxyFor(Platform.AMASIAN_TV)) {
            log.warn("Amasian TV 解析中止: 服务器网络被地区限制且未配置代理, url={}", url);
            throw new BusinessException("Amasian TV 仅限美洲地区访问，当前服务器网络被地区限制。"
                    + "请配置代理后重试（环境变量 PROXY_URL，如 http://127.0.0.1:7890）");
        }
        String slug = extractSlug(url);
        log.info("Amasian TV 解析开始: url={}, slug={}, proxy={}", url, slug,
                ytDlpService.needsProxyFor(Platform.AMASIAN_TV));

        // 1. content API：获取视频 id、标题、时长、封面
        JsonNode content = fetchJson(API_BASE + "/content/" + slug + "/");
        JsonNode result = content.path("result");
        int contentId = result.path("id").asInt(0);
        if (contentId == 0) {
            throw new BusinessException("无法从 Amasian TV 接口获取视频信息");
        }
        String title = result.path("title").asText(null);
        if (title == null || title.isBlank()) {
            String season = result.path("season").path("title").asText("");
            int ep = result.path("episode_order").asInt(0);
            title = season + (ep > 0 ? " : E" + ep : "");
        }
        long duration = result.path("duration").asLong(0);
        String thumbnail = result.path("thumbnail").path("url").asText(null);
        log.info("Amasian TV content 信息: id={}, title={}, duration={}s", contentId, title, duration);

        // 2. playback API：获取 HLS master m3u8 地址
        JsonNode playback = fetchJson(API_BASE + "/playback/" + contentId + "/");
        String streamUrl = playback.path("result").path("manifests").path("stream_url").asText(null);
        if (streamUrl == null || streamUrl.isBlank()) {
            log.warn("Amasian TV playback 响应缺少 stream_url, contentId={}, body={}",
                    contentId, snippet(playback.toString()));
            throw new BusinessException("无法获取 Amasian TV 视频流地址");
        }
        log.info("Amasian TV 获取到 master m3u8: {}", streamUrl);

        return buildVideoInfo(url, streamUrl, String.valueOf(contentId), title, duration, thumbnail,
                result.path("season").path("title").asText(null), result.path("publish_start").asText(null));
    }

    /**
     * 按清晰度下载（HLS，走 HlsClient 的 ffmpeg 下载）。未解析过则先解析。
     */
    public void download(String url, String formatId, String title,
                         HttpServletResponse response, String taskId) {
        String stream = resolveStreamUrl(url, formatId);
        hlsClient.downloadToResponse(stream,
                title != null ? title : "amasian-video", null, response, taskId);
    }

    /**
     * 从缓存中解析出指定清晰度的 variant m3u8 URL。未解析过则先解析。
     */
    public String resolveStreamUrl(String url, String formatId) {
        Map<String, String> formatMap = cache.get(url);
        if (formatMap == null) {
            parse(new ParseContext(url, null, null));
            formatMap = cache.get(url);
        }
        String stream = formatMap != null ? formatMap.get(formatId) : null;
        if (stream == null) {
            stream = formatMap != null ? formatMap.values().stream().findFirst().orElse(null) : null;
        }
        if (stream == null) {
            throw new BusinessException("请先解析视频后再下载");
        }
        return stream;
    }

    /**
     * 获取缓存的字幕轨道列表。未解析过则先解析。
     * 下载时取第一条字幕轨道的 URI，用 ffmpeg 下载为 .vtt 后嵌入视频。
     */
    public List<HlsClient.SubtitleTrack> getSubtitleTracks(String url) {
        List<HlsClient.SubtitleTrack> tracks = subtitleCache.get(url);
        if (tracks == null) {
            parse(new ParseContext(url, null, null));
            tracks = subtitleCache.get(url);
        }
        return tracks != null ? tracks : List.of();
    }

    /**
     * 获取缓存的 HLS master playlist URL。未解析过则先解析。
     * 下载字幕时需要 master URL（yt-dlp 从中发现字幕轨道）。
     */
    public String getMasterUrl(String url) {
        String masterUrl = masterUrlCache.get(url);
        if (masterUrl == null) {
            parse(new ParseContext(url, null, null));
            masterUrl = masterUrlCache.get(url);
        }
        return masterUrl;
    }

    private VideoInfo buildVideoInfo(String url, String streamUrl, String id, String title,
                                     long duration, String thumbnail, String uploader, String uploadDate) {
        masterUrlCache.put(url, streamUrl);
        List<HlsClient.HlsVariant> variants = hlsClient.parseManifest(streamUrl, null);

        // 解析 master playlist 中的字幕轨道并缓存（供下载时嵌入软字幕）
        List<HlsClient.SubtitleTrack> subtitleTracks = hlsClient.parseSubtitleTracks(streamUrl, null);
        subtitleCache.put(url, subtitleTracks);
        List<String> subtitleLangs = subtitleTracks.stream()
                .map(HlsClient.SubtitleTrack::language)
                .filter(lang -> lang != null && !lang.isBlank())
                .distinct()
                .toList();
        log.info("Amasian TV 字幕轨道: {} 条 -> {}", subtitleTracks.size(), subtitleLangs);

        List<FormatInfo> formats = new ArrayList<>();
        Map<String, String> formatMap = new ConcurrentHashMap<>();
        if (variants.isEmpty()) {
            formats.add(new FormatInfo(
                    "amasian_default", "mp4", null, null,
                    null, null, null, null,
                    "默认", false, false, true));
            formatMap.put("amasian_default", streamUrl);
        } else {
            for (HlsClient.HlsVariant v : variants) {
                String resolution = v.height() > 0 ? v.width() + "x" + v.height() : null;
                String formatId = "amasian_" + (v.height() > 0 ? v.height() : "auto");
                formats.add(new FormatInfo(
                        formatId, "mp4", resolution, v.height() > 0 ? v.height() : null,
                        null, null, null, null,
                        v.label(), false, false, true));
                formatMap.put(formatId, v.uri());
            }
        }

        cache.put(url, formatMap);
        return new VideoInfo(
                id,
                title, thumbnail,
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                uploader,
                Platform.AMASIAN_TV.display,
                null,
                uploadDate,
                formats, null,
                subtitleLangs.isEmpty() ? List.of() : subtitleLangs,
                !subtitleLangs.isEmpty());
    }

    /**
     * 从页面 URL 末段提取 content slug（/video/series/xxx/yyy → yyy）。
     */
    private static String extractSlug(String url) {
        try {
            String path = URI.create(url).getPath();
            String[] segments = path.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (!segments[i].isBlank()) {
                    return segments[i];
                }
            }
        } catch (Exception ignored) {
        }
        throw new BusinessException("无法从 Amasian TV 链接中提取视频标识");
    }

    private JsonNode fetchJson(String apiUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Referer", REFERER)
                    .header("Origin", "https://amasian.tv")
                    .GET().build();
            HttpResponse<String> resp = httpClient().send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                String body = resp.body();
                // Amasian TV（odkmedia.io）对被禁地区返回 403 REGION_BLOCK 或 404
                if (body != null && (body.contains("REGION_BLOCK")
                        || body.contains("not available in your region"))) {
                    throw new BusinessException("Amasian TV 仅限美洲地区访问，当前请求出口 IP 被地区限制。"
                            + "请配置（或更换为）美洲地区代理，环境变量 PROXY_URL，如 http://127.0.0.1:7890");
                }
                throw new BusinessException("Amasian TV 接口返回状态码 " + resp.statusCode()
                        + (body != null && !body.isBlank() ? "：" + snippet(body) : ""));
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("调用 Amasian TV 接口失败：" + e.getMessage());
        }
    }

    private static String snippet(String body) {
        return body.length() > 150 ? body.substring(0, 150) : body;
    }
}
