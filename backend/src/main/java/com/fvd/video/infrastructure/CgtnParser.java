package com.fvd.video.infrastructure;

import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * CGTN（news.cgtn.com）视频解析。
 * 流程：页面取 data-video 属性（m3u8 master playlist）→ HlsClient 解析变体。
 * 下载使用 HlsClient（ffmpeg 原生 HLS 下载），不经过 yt-dlp。
 */
@Slf4j
@Service
public class CgtnParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern DATA_VIDEO_RE = Pattern.compile("data-video\\s*=\\s*\"([^\"]+\\.m3u8[^\"]*)\"");
    private static final Pattern TITLE_RE = Pattern.compile("<title>([^<]+)</title>");
    private static final Pattern POSTER_RE = Pattern.compile("data-poster=\"([^\"]+)\"");
    private static final Pattern NEWS_ID_RE = Pattern.compile("var\\s+newsId\\s*=\\s*'([^']+)'");

    private final int parseTimeout;
    private final HlsClient hlsClient;
    private final HttpClient client;
    /** 解析缓存：页面 URL → formatId → 变体流地址（供下载时按清晰度反查） */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public CgtnParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                      HlsClient hlsClient) {
        this.parseTimeout = parseTimeout;
        this.hlsClient = hlsClient;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean supports(String url) {
        return Platform.from(url) == Platform.CGTN;
    }

    public VideoInfo parse(String url, String cookies) {
        String html = fetchPage(url);
        String m3u8Url = extractDataVideo(html);
        if (m3u8Url == null || m3u8Url.isBlank()) {
            throw new BusinessException("无法从 CGTN 页面提取视频地址");
        }
        log.info("CGTN 解析: url={}, m3u8={}", url, m3u8Url);

        String title = extractTitle(html);
        String poster = extractPoster(html);
        String newsId = extractNewsId(html);

        // 解析 m3u8 master playlist 获取清晰度变体
        List<HlsClient.HlsVariant> variants = hlsClient.parseManifest(m3u8Url, null);

        List<FormatInfo> formats = new ArrayList<>();
        Map<String, String> formatMap = new ConcurrentHashMap<>();
        if (variants.isEmpty()) {
            formats.add(new FormatInfo(
                    "cgtn_default", "mp4", null, null,
                    null, null, null, null,
                    "默认", false, false, true));
            formatMap.put("cgtn_default", m3u8Url);
        } else {
            for (HlsClient.HlsVariant v : variants) {
                String resolution = v.height() > 0 ? v.width() + "x" + v.height() : null;
                String formatId = "cgtn_" + (v.height() > 0 ? v.height() : "auto");
                formats.add(new FormatInfo(
                        formatId, "mp4", resolution, v.height() > 0 ? v.height() : null,
                        null, null, null, null,
                        v.label(), false, false, true));
                formatMap.put(formatId, v.uri());
            }
        }

        cache.put(url, formatMap);
        return new VideoInfo(
                newsId != null ? newsId : url,
                title, poster,
                null, null,
                "CGTN",
                Platform.CGTN.display,
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
            parse(url, null);
            formatMap = cache.get(url);
        }
        String streamUrl = formatMap != null ? formatMap.get(formatId) : null;
        if (streamUrl == null) {
            // formatId 不匹配时回退到第一个可用流
            streamUrl = formatMap != null ? formatMap.values().stream().findFirst().orElse(null) : null;
        }
        if (streamUrl == null) {
            throw new BusinessException("请先解析视频后再下载");
        }
        hlsClient.downloadToResponse(streamUrl,
                title != null ? title : "cgtn-video", null, response, taskId);
    }

    private String fetchPage(String pageUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(pageUrl))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("CGTN 页面返回状态码 " + resp.statusCode());
            }
            return resp.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取 CGTN 页面失败：" + e.getMessage());
        }
    }

    private String extractDataVideo(String html) {
        Matcher m = DATA_VIDEO_RE.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String extractTitle(String html) {
        Matcher m = TITLE_RE.matcher(html);
        if (m.find()) {
            String t = m.group(1).trim();
            // 去掉 " - CGTN" 后缀
            if (t.endsWith(" - CGTN")) {
                t = t.substring(0, t.length() - 7).trim();
            }
            return t;
        }
        return "CGTN 视频";
    }

    private String extractPoster(String html) {
        Matcher m = POSTER_RE.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String extractNewsId(String html) {
        Matcher m = NEWS_ID_RE.matcher(html);
        return m.find() ? m.group(1) : null;
    }
}
