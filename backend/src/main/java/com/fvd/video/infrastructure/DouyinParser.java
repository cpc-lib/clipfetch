package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;

/**
 * 抖音专用解析模块：
 * 1. 短链（v.douyin.com）跟随重定向拿到真实视频 ID
 * 2. 请求 iesdouyin 分享页，提取 _ROUTER_DATA 内的 JSON
 * 3. 拼接无水印播放地址（playwm → play）
 * 失败时回退到 yt-dlp 通用解析
 */
@Slf4j
@Component
public class DouyinParser {

    private static final Pattern VIDEO_ID = Pattern.compile("/(?:video|note)/(\\d+)|modal_id=(\\d+)");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[\\w.-]+/\\S+");
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 解析结果短期缓存（直链有时效性），10 分钟过期 */
    private record CacheEntry(ParsedVideo video, Instant expireAt) {
    }

    /** 抖音解析中间结果 */
    public record ParsedVideo(String videoId, String title, String uploader, String cover,
                              long durationMs, String playUrl) {
    }

    private final YtDlpService ytDlp;

    public DouyinParser(YtDlpService ytDlp) {
        this.ytDlp = ytDlp;
    }

    public boolean supports(String url) {
        return Platform.from(url) == Platform.DOUYIN;
    }

    // ===== 对外：解析 =====

    public VideoInfo parse(String url, String userCookieContent) {
        // 优先：分享页 SSR 数据（若抖音恢复内嵌则免 Cookie 可用）
        try {
            ParsedVideo v = parseVideo(resolveVideoId(cleanUrl(url), userCookieContent), userCookieContent);
            return new VideoInfo(
                    v.videoId(),
                    v.title(),
                    v.cover(),
                    v.durationMs() > 0 ? v.durationMs() / 1000 : null,
                    v.durationMs() > 0 ? YtDlpService.formatDuration(v.durationMs() / 1000) : null,
                    v.uploader(),
                    Platform.DOUYIN.display,
                    null,
                    null,
                    List.of(new FormatInfo("douyin_nowm", "mp4", "1080x1920", 1080,
                            null, null, "avc1", "mp4a", "无水印 原画 MP4", false, false, true)),
                    List.of(),
                    false
            );
        } catch (BusinessException e) {
            log.info("抖音分享页解析失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("抖音分享页解析异常: {}", e.getMessage());
        }
        // 回退：yt-dlp（使用当前登录用户上传的抖音 cookies）
        return ytDlp.parse(url, userCookieContent);
    }

    // ===== 对外：无水印直链 =====

    /**
     * 尝试获取抖音无水印直链；分享页数据不可用时返回 null（由调用方回退 yt-dlp）
     */
    public String tryDirectUrl(String url, String userCookieContent) {
        try {
            return parseVideo(resolveVideoId(cleanUrl(url), userCookieContent), userCookieContent).playUrl();
        } catch (Exception e) {
            log.info("抖音直链（分享页）不可用: {}", e.getMessage());
            return null;
        }
    }

    // ===== 内部实现 =====

    private String cleanUrl(String input) {
        if (input == null) {
            throw new BusinessException("链接不能为空");
        }
        input = input.trim();
        if (input.startsWith("http")) {
            return input;
        }
        Matcher m = URL_IN_TEXT.matcher(input);
        if (m.find()) {
            return m.group();
        }
        throw new BusinessException("未识别到有效的抖音链接");
    }

    private String resolveVideoId(String url, String userCookieContent) {
        String cookieHeader = CookieService.toCookieHeader(userCookieContent, Platform.DOUYIN);
        Matcher m = VIDEO_ID.matcher(url);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        // 短链：跟随重定向
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(current))
                    .header("User-Agent", MOBILE_UA);
            if (!cookieHeader.isEmpty()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpResponse<Void> resp;
            try {
                resp = http.send(rb.GET().build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                throw new BusinessException("访问抖音链接失败：" + e.getMessage());
            }
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String location = resp.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    break;
                }
                Matcher idm = VIDEO_ID.matcher(location);
                if (idm.find()) {
                    return idm.group(1) != null ? idm.group(1) : idm.group(2);
                }
                current = location;
                continue;
            }
            break;
        }
        throw new BusinessException("无法从链接中识别视频 ID");
    }

    private ParsedVideo parseVideo(String videoId, String userCookieContent) {
        CacheEntry cached = cache.get(videoId);
        if (cached != null && cached.expireAt().isAfter(Instant.now())) {
            return cached.video();
        }
        ParsedVideo parsed = fetchFromSharePage(videoId, userCookieContent);
        cache.put(videoId, new CacheEntry(parsed, Instant.now().plusSeconds(600)));
        return parsed;
    }

    private ParsedVideo fetchFromSharePage(String videoId, String userCookieContent) {
        String cookieHeader = CookieService.toCookieHeader(userCookieContent, Platform.DOUYIN);
        String html;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("https://www.iesdouyin.com/share/video/" + videoId))
                    .header("User-Agent", MOBILE_UA)
                    .timeout(Duration.ofSeconds(15));
            if (!cookieHeader.isEmpty()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpResponse<String> resp = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
            html = resp.body();
        } catch (Exception e) {
            throw new BusinessException("请求抖音分享页失败：" + e.getMessage());
        }
        JsonNode routerData = extractRouterData(html);
        if (routerData == null) {
            throw new BusinessException("抖音页面数据解析失败");
        }

        JsonNode itemList = null;
        JsonNode loaderData = routerData.path("loaderData");
        if (loaderData.isObject()) {
            var it = loaderData.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                if (key.contains("video") || key.contains("note")) {
                    JsonNode candidate = loaderData.path(key).path("videoInfoRes").path("item_list");
                    if (candidate.isArray() && candidate.size() > 0) {
                        itemList = candidate;
                        break;
                    }
                }
            }
        }
        if (itemList == null) {
            throw new BusinessException("未找到视频数据，该链接可能是图集或已删除");
        }
        JsonNode item = itemList.get(0);

        JsonNode playAddr = item.path("video").path("play_addr");
        String playUrl = firstUrl(playAddr);
        if (playUrl == null || playUrl.isBlank()) {
            throw new BusinessException("未获取到播放地址");
        }
        // playwm → play 得到无水印地址
        playUrl = playUrl.replace("playwm", "play");
        if (!playUrl.startsWith("http")) {
            playUrl = "https://www.douyin.com/aweme/v1/play/?video_id=" + playUrl;
        }

        String desc = item.path("desc").asText("抖音视频");
        String uploader = item.path("author").path("nickname").asText("未知作者");
        String cover = firstUrl(item.path("video").path("cover"));
        if (cover == null) {
            cover = firstUrl(item.path("video").path("dynamic_cover"));
        }
        long durationMs = item.path("video").path("duration").asLong(0);
        return new ParsedVideo(videoId, desc, uploader, cover, durationMs, playUrl);
    }

    private String firstUrl(JsonNode addrNode) {
        JsonNode urls = addrNode.path("url_list");
        if (urls.isArray() && urls.size() > 0) {
            return urls.get(0).asText(null);
        }
        String uri = addrNode.path("uri").asText(null);
        return uri;
    }

    /**
     * 从 HTML 中提取 window._ROUTER_DATA = {...} 的 JSON（括号配平）
     */
    private JsonNode extractRouterData(String html) {
        int keyIdx = html.indexOf("_ROUTER_DATA");
        if (keyIdx < 0) {
            return null;
        }
        int start = html.indexOf('{', keyIdx);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return mapper.readTree(html.substring(start, i + 1));
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
