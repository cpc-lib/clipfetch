package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MissAV 页面解析：从页面中的 seek 缩略图地址还原 surrit.com HLS 主清单。
 */
@Slf4j
@Service
public class MissavParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern SURRIT_VIDEO_ID = Pattern.compile(
            "https?://surrit\\.com/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/(?:seek/|playlist\\.m3u8)");
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_KEY = Pattern.compile(
            "(?:property|name)\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_CONTENT = Pattern.compile(
            "content\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEIGHT = Pattern.compile("(?:x|\\b)(\\d{3,4})p?\\b", Pattern.CASE_INSENSITIVE);

    private final YtDlpService ytDlp;
    private final int parseTimeout;
    private final HttpClient client;

    public MissavParser(YtDlpService ytDlp,
                        @Value("${app.parse-timeout:60}") int parseTimeout,
                        @Value("${app.proxy:}") String proxy) {
        this.ytDlp = ytDlp;
        this.parseTimeout = parseTimeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(parseTimeout))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null && !proxy.isBlank()) {
            URI proxyUri = URI.create(proxy);
            if (proxyUri.getHost() != null && proxyUri.getPort() > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort())));
            }
        }
        this.client = builder.build();
    }

    public boolean supports(String url) {
        return supportsUrl(url);
    }

    static boolean supportsUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            host = host.toLowerCase();
            return host.equals("missav.ws") || host.endsWith(".missav.ws");
        } catch (Exception e) {
            return false;
        }
    }

    public VideoInfo parse(String url) {
        log.info("[MissAV] 开始解析: {}", url);
        PageData page = fetchPage(url);
        log.info("[MissAV] 页面解析完成: id={}, title={}, masterUrl={}",
                page.id(), page.title(), page.masterUrl());
        log.info("[MissAV] 调用 yt-dlp 解析 HLS 清单...");
        JsonNode raw = ytDlp.dumpInfo(page.masterUrl(), null, ytDlpArgs(page.pageUrl()));
        VideoInfo info = mapInfo(page, raw);
        log.info("[MissAV] 解析成功: {} 个清晰度, 时长={}",
                info.formats() != null ? info.formats().size() : 0,
                info.durationString());
        return info;
    }

    /**
     * 下载时重新读取页面，避免长期缓存已经轮换的 CDN 视频 ID。
     */
    public DownloadTarget resolveDownload(String url) {
        log.info("[MissAV] 重新解析页面以获取下载地址: {}", url);
        PageData page = fetchPage(url);
        log.info("[MissAV] 下载地址就绪: {}", page.masterUrl());
        return new DownloadTarget(page.masterUrl(), ytDlpArgs(page.pageUrl()));
    }

    private PageData fetchPage(String url) {
        if (!supportsUrl(url)) {
            throw new BusinessException("不是有效的 MissAV 视频链接");
        }
        try {
            URI current = URI.create(url);
            for (int redirects = 0; redirects <= 3; redirects++) {
                log.debug("[MissAV] 请求页面 (第{}次跳转): {}", redirects, current);
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(parseTimeout))
                        .header("User-Agent", UA)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET()
                        .build();
                HttpResponse<String> response = sendWithRetry(request);
                int code = response.statusCode();
                log.info("[MissAV] 页面响应 HTTP {} ({}字节)", code,
                        response.body() != null ? response.body().length() : 0);
                if (code >= 300 && code < 400) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new BusinessException("MissAV 页面重定向缺少地址"));
                    current = current.resolve(location);
                    if (!supportsUrl(current.toString())) {
                        throw new BusinessException("MissAV 页面重定向到了不受信任的域名");
                    }
                    log.info("[MissAV] 重定向到: {}", current);
                    continue;
                }
                if (code != 200) {
                    throw new BusinessException("MissAV 页面访问失败（HTTP " + code + "）");
                }
                return extractPage(current.toString(), response.body());
            }
            throw new BusinessException("MissAV 页面重定向次数过多");
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("MissAV 页面请求被中断");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = e.getClass().getSimpleName();
            }
            log.warn("[MissAV] 页面请求异常: {}", msg);
            throw new BusinessException("MissAV 页面请求失败：" + msg);
        }
    }

    /**
     * MissAV 偶发 403/429（限流或反爬挑战），重试即可成功。
     * 对 403/429/5xx 自动重试最多 4 次，间隔 1.5s 线性退避。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request)
            throws java.io.IOException, InterruptedException {
        HttpResponse<String> response = null;
        int maxRetries = 4;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            // 200 / 3xx 直接返回
            if (code == 200 || (code >= 300 && code < 400)) {
                if (attempt > 0) {
                    log.info("[MissAV] 第{}次重试成功 HTTP {}", attempt, code);
                }
                return response;
            }
            // 403/429/5xx 重试
            if ((code == 403 || code == 429 || code >= 500) && attempt < maxRetries) {
                long waitMs = 1500L * (attempt + 1);
                log.warn("[MissAV] HTTP {}，{}/{} 秒后重试...", code, waitMs / 1000.0, (attempt + 1));
                Thread.sleep(waitMs);
                continue;
            }
            log.warn("[MissAV] HTTP {} 不在重试范围内，返回", code);
            return response;
        }
        return response;
    }

    static PageData extractPage(String pageUrl, String html) {
        String normalized = html == null ? "" : html.replace("\\/", "/");
        Matcher stream = SURRIT_VIDEO_ID.matcher(normalized);
        if (!stream.find()) {
            log.warn("[MissAV] 页面中未找到 surrit 视频流 ID，页面长度={}", normalized.length());
            throw new BusinessException("未获取到 MissAV 视频流，该页面可能已失效或触发了访问验证");
        }
        String videoId = stream.group(1);
        String id = lastPathSegment(URI.create(pageUrl).getPath());
        String title = metaContent(normalized, "og:title");
        String thumbnail = metaContent(normalized, "og:image");
        String masterUrl = "https://surrit.com/" + videoId + "/playlist.m3u8";
        log.info("[MissAV] 提取视频流: surritId={}, pageId={}, title={}", videoId, id, title);
        return new PageData(pageUrl, id, title == null || title.isBlank() ? "MissAV 视频" : title,
                thumbnail, masterUrl);
    }

    static VideoInfo mapInfo(PageData page, JsonNode raw) {
        Map<Integer, FormatInfo> byHeight = new LinkedHashMap<>();
        JsonNode rawFormats = raw.path("formats");
        if (rawFormats.isArray()) {
            for (JsonNode format : rawFormats) {
                FormatInfo mapped = mapFormat(format);
                if (mapped != null) {
                    byHeight.put(mapped.height(), mapped);
                }
            }
        }
        List<FormatInfo> formats = new ArrayList<>(byHeight.values());
        formats.sort(Comparator.comparing(FormatInfo::height).reversed());
        log.info("[MissAV] yt-dlp 原始格式 {} 个，过滤后 {} 个清晰度: {}",
                rawFormats.isArray() ? rawFormats.size() : 0,
                formats.size(),
                formats.stream().map(f -> f.height() + "p").toList());
        if (formats.isEmpty()) {
            throw new BusinessException("未获取到 MissAV 视频清晰度，请稍后重试");
        }

        long duration = raw.path("duration").asLong(0);
        String thumbnail = page.thumbnail() != null ? page.thumbnail() : raw.path("thumbnail").asText(null);
        return new VideoInfo(page.id(), page.title(), thumbnail,
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                raw.path("uploader").asText(null), "MissAV",
                null, null, formats, null, List.of(), false);
    }

    private static FormatInfo mapFormat(JsonNode raw) {
        String formatId = raw.path("format_id").asText("");
        String ext = raw.path("ext").asText("mp4");
        if (formatId.isBlank() || switch (ext.toLowerCase()) {
            case "mhtml", "jpg", "jpeg", "png", "webp" -> true;
            default -> false;
        }) {
            return null;
        }

        String vcodec = raw.path("vcodec").asText("none");
        String acodec = raw.path("acodec").asText("none");
        if ("none".equals(vcodec) && !"none".equals(acodec)) {
            return null;
        }
        Integer height = raw.path("height").isNumber() ? raw.path("height").asInt() : null;
        if (height == null) {
            for (String field : List.of("resolution", "format_note", "format")) {
                Matcher matcher = HEIGHT.matcher(raw.path(field).asText(""));
                if (matcher.find()) {
                    height = Integer.parseInt(matcher.group(1));
                    break;
                }
            }
        }
        if (height == null) {
            return null;
        }

        String resolution = raw.path("resolution").asText(null);
        Long filesize = raw.path("filesize").isNumber() ? raw.path("filesize").asLong() : null;
        Long filesizeApprox = raw.path("filesize_approx").isNumber()
                ? raw.path("filesize_approx").asLong() : null;
        return new FormatInfo(formatId, ext, resolution, height, filesize, filesizeApprox,
                "none".equals(vcodec) ? null : vcodec,
                "none".equals(acodec) ? null : acodec,
                height + "p", !"none".equals(vcodec) && "none".equals(acodec), false, true);
    }

    static List<String> ytDlpArgs(String pageUrl) {
        URI page = URI.create(pageUrl);
        String origin = page.getScheme() + "://" + page.getHost()
                + (page.getPort() >= 0 ? ":" + page.getPort() : "");
        return List.of(
                "--impersonate", "chrome",
                "--referer", pageUrl,
                "--add-headers", "Origin:" + origin,
                "--add-headers", "Accept-Language:en-US,en;q=0.9",
                "--add-headers", "Sec-Fetch-Dest:empty",
                "--add-headers", "Sec-Fetch-Mode:cors",
                "--add-headers", "Sec-Fetch-Site:cross-site",
                "--socket-timeout", "90",
                "--concurrent-fragments", "128",
                "--fragment-retries", "20",
                "--retry-sleep", "fragment:linear=1:5:1",
                "--downloader", "m3u8:native");
    }

    private static String metaContent(String html, String wantedKey) {
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

    private static String lastPathSegment(String path) {
        if (path != null) {
            String[] segments = path.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (!segments[i].isBlank()) {
                    return segments[i];
                }
            }
        }
        return "missav-video";
    }

    record PageData(String pageUrl, String id, String title, String thumbnail, String masterUrl) {
    }

    public record DownloadTarget(String masterUrl, List<String> ytDlpArgs) {
    }
}
