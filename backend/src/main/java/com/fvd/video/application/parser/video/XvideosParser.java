package com.fvd.video.application.parser.video;

import com.fvd.video.application.ParseContext;
import com.fvd.video.application.parser.AbstractVideoParser;
import com.fvd.video.application.parser.CookiePolicy;
import com.fvd.video.infrastructure.service.HlsClient;
import com.fvd.video.infrastructure.service.YtDlpService;

import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XVideos 页面解析：提取播放器 HLS 主清单，并复用 HlsClient 解析和下载清晰度变体。
 */
@Service
public class XvideosParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern VIDEO_PATH = Pattern.compile(
            "^/video(?:\\.[^/]+|\\d+)(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID = Pattern.compile(
            "^/video(?:\\.([^/]+)|(\\d+))(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_KEY = Pattern.compile(
            "(?:property|name)\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_CONTENT = Pattern.compile(
            "content\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private final int parseTimeout;
    private final HlsClient hlsClient;
    private final YtDlpService ytDlpService;
    private final HttpClient directClient;
    private volatile HttpClient proxyClient;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public XvideosParser(@Value("${app.parse-timeout:60}") int parseTimeout,
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
        return Platform.XVIDEOS;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    /** XVideos 被墙时走代理的 HttpClient（懒加载） */
    private HttpClient httpClient() {
        if (!ytDlpService.needsProxyFor(Platform.XVIDEOS)) {
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
                }
            }
        }
        return proxyClient;
    }

    @Override
    public boolean supports(String url) {
        return supportsUrl(url);
    }

    static boolean supportsUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && Platform.from(url) == Platform.XVIDEOS
                    && VIDEO_PATH.matcher(uri.getPath()).find();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        if (!supportsUrl(url)) {
            throw new BusinessException("不是有效的 XVideos 视频链接");
        }
        PageData page = extractPage(url, fetchPage(url));
        // 大陆不可达时 manifest 也走代理，美国直连（CDN 域名平台探测识别不到，按 XVIDEOS 平台判断）
        String proxyOverride = ytDlpService.needsProxyFor(Platform.XVIDEOS)
                ? ytDlpService.proxy() : null;
        List<HlsClient.HlsVariant> variants = hlsClient.parseManifest(
                page.masterUrl(), null, proxyOverride);
        if (variants.isEmpty()) {
            throw new BusinessException("未获取到 XVideos 视频清晰度，请稍后重试");
        }

        List<FormatInfo> formats = new ArrayList<>();
        Map<String, String> streams = new LinkedHashMap<>();
        for (int i = 0; i < variants.size(); i++) {
            HlsClient.HlsVariant variant = variants.get(i);
            String suffix = variant.height() > 0 ? String.valueOf(variant.height()) : "auto" + i;
            String formatId = "xvideos_" + suffix;
            String resolution = variant.height() > 0
                    ? variant.width() + "x" + variant.height() : null;
            // Xvideos 的 CDN 直链可直连下载，标记 directUrl 让前端直接请求，
            // 避免 yt-dlp generic 提取器对高分辨率 m3u8 返回 400 Bad Request
            formats.add(new FormatInfo(
                    formatId, "mp4", resolution,
                    variant.height() > 0 ? variant.height() : null,
                    null, null, null, null,
                    variant.label(), false, true, true));
            streams.put(formatId, variant.uri());
        }
        cache.put(url, Collections.unmodifiableMap(streams));

        Long duration = page.duration();
        return new VideoInfo(
                page.id(), page.title(), page.thumbnail(), duration,
                duration != null ? YtDlpService.formatDuration(duration) : null,
                null, Platform.XVIDEOS.display, null, null,
                formats, null, List.of(), false);
    }

    /**
     * 按清晰度 formatId 反查 HLS 变体地址（具体清晰度的 media playlist）。
     * 注意：Xvideos CDN URL 带签名时间戳，缓存的 URL 会过期，每次下载必须重新解析。
     */
    public String resolveStreamUrl(String url, String formatId) {
        // 不使用缓存：CDN 签名 URL 有时效性，必须重新解析页面获取最新地址
        VideoInfo info = parse(new ParseContext(url, null, null));
        Map<String, String> streams = new LinkedHashMap<>();
        for (FormatInfo f : info.formats()) {
            streams.put(f.formatId(), resolveStreamUrlInternal(url, f.formatId()));
        }
        String streamUrl = streams.get(formatId);
        if (streamUrl == null && !streams.isEmpty()) {
            streamUrl = streams.values().stream().findFirst().orElse(null);
        }
        return streamUrl;
    }

    private String resolveStreamUrlInternal(String url, String formatId) {
        Map<String, String> cached = cache.get(url);
        if (cached == null) {
            return null;
        }
        return cached.get(formatId);
    }

    private String fetchPage(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET().build();
            HttpResponse<String> response = httpClient().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new BusinessException("XVideos 页面返回状态码 " + response.statusCode());
            }
            return response.body();
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("XVideos 页面请求被中断");
        } catch (Exception e) {
            throw new BusinessException("获取 XVideos 页面失败：" + e.getMessage());
        }
    }

    static PageData extractPage(String pageUrl, String html) {
        String masterUrl = playerValue(html, "setVideoHLS");
        if (masterUrl == null || masterUrl.isBlank()) {
            throw new BusinessException("无法从 XVideos 页面提取视频地址");
        }
        String title = metaContent(html, "og:title");
        String thumbnail = metaContent(html, "og:image");
        Long duration = parseLong(metaContent(html, "og:duration"));
        return new PageData(extractId(pageUrl),
                title == null || title.isBlank() ? "XVideos 视频" : title,
                thumbnail, duration, masterUrl);
    }

    private static String playerValue(String html, String method) {
        if (html == null) {
            return null;
        }
        Pattern pattern = Pattern.compile(
                "html5player\\." + Pattern.quote(method)
                        + "\\s*\\(\\s*([\"'])((?:\\\\.|(?!\\1).)*)\\1\\s*\\)",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? decodeJsString(matcher.group(2)) : null;
    }

    private static String decodeJsString(String value) {
        Matcher matcher = UNICODE_ESCAPE.matcher(value);
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            char replacement = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf(replacement)));
        }
        matcher.appendTail(decoded);
        return HtmlUtils.htmlUnescape(decoded.toString()
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\"));
    }

    private static String metaContent(String html, String wantedKey) {
        if (html == null) {
            return null;
        }
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Matcher key = META_KEY.matcher(tag);
            Matcher content = META_CONTENT.matcher(tag);
            if (key.find() && wantedKey.equalsIgnoreCase(key.group(2)) && content.find()) {
                return HtmlUtils.htmlUnescape(content.group(2).trim());
            }
        }
        return null;
    }

    private static Long parseLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractId(String pageUrl) {
        Matcher matcher = VIDEO_ID.matcher(URI.create(pageUrl).getPath());
        if (!matcher.find()) {
            return "xvideos-video";
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    record PageData(String id, String title, String thumbnail, Long duration, String masterUrl) {
    }
}
