package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯视频解析：getinfo 获取元数据与渐进式 MP4 直链（vkey）。
 * 匿名只能取到约 5 分钟试看片段（正片在转码 HLS 节点被限速约 1KB/s）；
 * 上传 v.qq.com 登录 cookies 后，getinfo/getkey 携带 Cookie 可返回 VIP 正片的完整 vkey，
 * download 节点（video.dispatch.tc.qq.com 等）满速直连并支持 Range。
 * 国内 CDN 直连，无需代理。
 */
@Slf4j
@Service
public class TencentParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    /** 搜索引擎 UA 可命中播放页 SSR 版本，其中含 og:image 封面（普通 UA 仅返回 SPA 外壳） */
    private static final String SPIDER_UA = "Mozilla/5.0 (compatible; Baiduspider/2.0; "
            + "+http://www.baidu.com/search/spider.html)";
    private static final String GETINFO_URL = "https://vv.video.qq.com/getinfo";
    private static final String GETKEY_URL = "https://vv.video.qq.com/getkey";
    private static final Pattern RANGE_TOTAL = Pattern.compile("/(\\d+)\\s*$");
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+property=[\"']og:image[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CONTENT = Pattern.compile(
            "content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** /x/cover/{cid}/{vid}.html 或 /x/page/{vid}.html */
    private static final Pattern VID_PATH = Pattern.compile(
            "^/x/(?:cover/[^/]+/|page/)?([A-Za-z0-9]+)\\.html", Pattern.CASE_INSENSITIVE);
    private static final Pattern VID_QUERY = Pattern.compile("(?:^|&)vid=([A-Za-z0-9]+)");
    private static final Pattern FID_SUFFIX = Pattern.compile("\\.f(\\d+)$");

    private final int parseTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TencentParser(@Value("${app.parse-timeout:60}") int parseTimeout) {
        this.parseTimeout = parseTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean supports(String url) {
        return supportsUrl(url);
    }

    static boolean supportsUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && Platform.from(url) == Platform.TENCENT
                    && extractVid(uri) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public VideoInfo parse(String url, String cookieContent) {
        if (!supportsUrl(url)) {
            throw new BusinessException("不是有效的腾讯视频链接");
        }
        String vid = extractVid(URI.create(url));
        String cookieHeader = CookieService.toCookieHeader(cookieContent, Platform.TENCENT);
        JsonNode root = fetchInfo(vid, cookieHeader);
        JsonNode viList = root.path("vl").path("vi");
        if (!viList.isArray() || viList.isEmpty()) {
            throw new BusinessException("未获取到腾讯视频播放信息（可能为付费内容或登录态已失效）");
        }

        // fl.fi 为清晰度规格表（id/宽高/完整大小），按 fi.id 索引
        Map<Long, JsonNode> fiSpecs = new LinkedHashMap<>();
        for (JsonNode fi : root.path("fl").path("fi")) {
            if (fi.path("id").isNumber()) {
                fiSpecs.put(fi.path("id").asLong(), fi);
            }
        }

        List<Candidate> candidates = collectCandidates(vid, viList, fiSpecs, cookieHeader);
        Long duration = parseDuration(viList.get(0).path("td").asText(null));

        List<FormatInfo> formats = new ArrayList<>();
        for (Candidate c : candidates) {
            Long realSize = probeLength(c.url(), cookieHeader);
            if (realSize == null) {
                continue;
            }
            boolean preview = c.fullSize() > 0 && realSize < c.fullSize() * 95L / 100L;
            String label = (c.height() > 0 ? c.height() + "P " : "") + "MP4";
            if (preview) {
                long previewSeconds = duration != null && c.fullSize() > 0
                        ? Math.round(duration * (double) realSize / c.fullSize()) : 0;
                label += " 试看" + (previewSeconds > 0
                        ? YtDlpService.formatDuration(previewSeconds) : "")
                        + "（" + YtDlpService.humanSize(realSize) + "）";
            } else {
                label += " (" + YtDlpService.humanSize(realSize) + ")";
            }
            formats.add(new FormatInfo(
                    c.formatId(), "mp4",
                    c.width() > 0 && c.height() > 0 ? c.width() + "x" + c.height() : null,
                    c.height() > 0 ? c.height() : null,
                    realSize, null, null, null,
                    label, false, false, true));
        }
        if (formats.isEmpty()) {
            throw new BusinessException("该视频暂无可下载清晰度（VIP 内容请上传已登录会员账号的 cookies）");
        }

        String title = viList.get(0).path("ti").asText(null);
        String thumbnail = fetchThumbnail(url);
        log.info("腾讯视频解析完成: vid={} title={} 可下载清晰度{}档 cookies={}",
                vid, title, formats.size(), cookieHeader != null && !cookieHeader.isBlank());
        return new VideoInfo(
                vid, title != null ? title : "腾讯视频 " + vid, thumbnail, duration,
                duration != null ? YtDlpService.formatDuration(duration) : null,
                null, Platform.TENCENT.display, null, null,
                formats, null, List.of(), false);
    }

    /**
     * 按清晰度 formatId（f2/f10217 等）拼接渐进式 MP4 直链。
     * vkey 有时效性，不缓存：每次下载重新 getinfo/getkey 取最新 vkey。
     */
    public String resolveStreamUrl(String url, String formatId, String cookieContent) {
        String vid = extractVid(URI.create(url));
        String cookieHeader = CookieService.toCookieHeader(cookieContent, Platform.TENCENT);
        JsonNode root = fetchInfo(vid, cookieHeader);
        JsonNode viList = root.path("vl").path("vi");
        if (!viList.isArray() || viList.isEmpty()) {
            throw new BusinessException("获取腾讯视频下载地址失败：播放信息为空（登录态可能已失效）");
        }
        Map<Long, JsonNode> fiSpecs = new LinkedHashMap<>();
        for (JsonNode fi : root.path("fl").path("fi")) {
            if (fi.path("id").isNumber()) {
                fiSpecs.put(fi.path("id").asLong(), fi);
            }
        }
        for (Candidate c : collectCandidates(vid, viList, fiSpecs, cookieHeader)) {
            if (c.formatId().equals(formatId)) {
                return c.url();
            }
        }
        throw new BusinessException("获取腾讯视频下载地址失败，请重新解析后再试");
    }

    /**
     * 汇总可下载候选：getinfo 的 vl.vi（默认档）+ 登录 cookies 下 getkey 补齐其余清晰度
     */
    private List<Candidate> collectCandidates(String vid, JsonNode viList,
                                              Map<Long, JsonNode> fiSpecs, String cookieHeader) {
        Map<Long, Candidate> byFid = new LinkedHashMap<>();
        for (JsonNode vi : viList) {
            Long fid = parseFid(vi.path("keyid").asText(""));
            if (fid == null) {
                continue;
            }
            String fn = vi.path("fn").asText(null);
            String vkey = vi.path("fvkey").asText(null);
            if (fn == null || vkey == null) {
                continue;
            }
            byFid.put(fid, buildCandidate(fid, fn, vi.path("ul").path("ui"),
                    vi.path("vw").asInt(0), vi.path("vh").asInt(0),
                    vi.path("fs").asLong(0), vkey, fiSpecs));
        }
        // 仅登录用户尝试解锁更高清晰度（匿名 getkey 只返回限速试看，无意义）
        boolean loggedIn = cookieHeader != null && !cookieHeader.isBlank();
        if (loggedIn && viList.get(0).path("fn").asText(null) != null) {
            String baseFn = viList.get(0).path("fn").asText();
            String base = baseFn.replaceAll("\\.f\\d+\\.mp4$", "");
            JsonNode firstUi = viList.get(0).path("ul").path("ui");
            for (Long fiId : fiSpecs.keySet()) {
                if (byFid.containsKey(fiId)) {
                    continue;
                }
                String fn = base + ".f" + fiId + ".mp4";
                String key = fetchKey(vid, fiId, fn, cookieHeader);
                if (key != null) {
                    byFid.put(fiId, buildCandidate(fiId, fn, firstUi, 0, 0, 0, key, fiSpecs));
                }
            }
        }
        return new ArrayList<>(byFid.values());
    }

    private Candidate buildCandidate(long fid, String fn, JsonNode uiList,
                                     int viWidth, int viHeight, long viSize, String vkey,
                                     Map<Long, JsonNode> fiSpecs) {
        JsonNode spec = fiSpecs.get(fid);
        int width = spec != null && spec.path("width").asInt(0) > 0
                ? spec.path("width").asInt() : viWidth;
        int height = spec != null && spec.path("height").asInt(0) > 0
                ? spec.path("height").asInt() : viHeight;
        long fullSize = spec != null && spec.path("fs").asLong(0) > 0
                ? spec.path("fs").asLong() : viSize;
        String base = chooseUiBase(uiList);
        return new Candidate("f" + fid, fn, base + fn + "?vkey=" + vkey,
                width, height, fullSize);
    }

    /** 优先域名型下载节点（video.dispatch.tc.qq.com），其次首个节点 */
    private static String chooseUiBase(JsonNode uiList) {
        String first = null;
        if (uiList.isArray()) {
            for (JsonNode ui : uiList) {
                String u = ui.path("url").asText(null);
                if (u == null) {
                    continue;
                }
                if (first == null) {
                    first = u;
                }
                if (u.contains("dispatch.tc.qq.com")) {
                    return u;
                }
            }
        }
        if (first == null) {
            throw new BusinessException("腾讯视频未返回下载节点");
        }
        return first;
    }

    /** keyid（xxx.f2）→ fi.id 数字 2 */
    private static Long parseFid(String keyid) {
        Matcher m = FID_SUFFIX.matcher(keyid);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** Range 0-0 探测资源真实总大小（试看片段远小于规格表 fs）；无 Content-Range 返回 null */
    private Long probeLength(String directUrl, String cookieHeader) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(directUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .header("Referer", "https://v.qq.com/")
                    .header("Range", "bytes=0-0")
                    .GET();
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpResponse<Void> resp = httpClient.send(rb.build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 206) {
                log.warn("腾讯视频直链探测失败: HTTP {} {}", resp.statusCode(),
                        directUrl.substring(0, Math.min(80, directUrl.length())));
                return null;
            }
            String range = resp.headers().firstValue("Content-Range").orElse(null);
            if (range == null) {
                return null;
            }
            Matcher m = RANGE_TOTAL.matcher(range);
            return m.find() ? Long.parseLong(m.group(1)) : null;
        } catch (Exception e) {
            log.warn("腾讯视频直链探测异常: {}", e.getMessage());
            return null;
        }
    }

    /** 按清晰度取下载 vkey（登录 cookies 下可解锁 VIP 清晰度） */
    private String fetchKey(String vid, long fiId, String filename, String cookieHeader) {
        try {
            String query = "vid=" + enc(vid) + "&format=" + fiId
                    + "&filename=" + enc(filename) + "&platform=101001&otype=json&ran=" + Math.random();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(GETKEY_URL + "?" + query))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://v.qq.com/")
                    .GET();
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpResponse<String> response = httpClient.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = parseJsonp(response.body());
            String key = root.path("key").asText(null);
            if (key == null) {
                log.info("腾讯 getkey 未返回 f{} key: {}", fiId, root.path("msg").asText(""));
            }
            return key;
        } catch (Exception e) {
            log.warn("腾讯 getkey 请求失败 f{}: {}", fiId, e.getMessage());
            return null;
        }
    }

    /**
     * 抓取播放页 SSR 版本中的 og:image 封面。getinfo 不返回封面，普通 UA 访问播放页只拿到
     * SPA 外壳；搜索引擎 UA 命中 SEO 渲染页，meta 中带 puui.qpic.cn 封面地址。失败不阻断解析。
     */
    private String fetchThumbnail(String pageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(pageUrl))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", SPIDER_UA)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("腾讯视频封面页返回状态码 {}", response.statusCode());
                return null;
            }
            Matcher tag = OG_IMAGE.matcher(response.body());
            if (tag.find()) {
                Matcher src = META_CONTENT.matcher(tag.group());
                if (src.find()) {
                    return src.group(1);
                }
            }
            log.info("腾讯视频封面页未包含 og:image");
            return null;
        } catch (Exception e) {
            log.warn("腾讯视频封面获取失败: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode fetchInfo(String vid, String cookieHeader) {
        try {
            String query = "vids=" + enc(vid) + "&platform=101001&otype=json&charge=0"
                    + "&defaultfmt=auto&ran=" + Math.random();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(GETINFO_URL + "?" + query))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://v.qq.com/")
                    .GET();
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpResponse<String> response = httpClient.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new BusinessException("腾讯视频信息接口返回状态码 " + response.statusCode());
            }
            return parseJsonp(response.body());
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("腾讯视频信息请求被中断");
        } catch (Exception e) {
            throw new BusinessException("获取腾讯视频信息失败：" + e.getMessage());
        }
    }

    private JsonNode parseJsonp(String body) throws Exception {
        body = body.trim();
        if (body.startsWith("QZOutputJson=")) {
            body = body.substring("QZOutputJson=".length());
            if (body.endsWith(";")) {
                body = body.substring(0, body.length() - 1);
            }
        }
        return mapper.readTree(body);
    }

    private static Long parseDuration(String td) {
        try {
            double seconds = Double.parseDouble(td);
            return seconds > 0 ? Math.round(seconds) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String extractVid(URI uri) {
        if (uri == null || uri.getPath() == null) {
            return null;
        }
        Matcher matcher = VID_PATH.matcher(uri.getPath());
        if (matcher.find()) {
            return matcher.group(1);
        }
        // /x/play?vid=xxx 形式兜底
        String query = uri.getQuery();
        if (query != null) {
            Matcher q = VID_QUERY.matcher(query);
            if (q.find()) {
                return q.group(1);
            }
        }
        return null;
    }

    /** 可下载候选：formatId、文件名、完整直链、宽高、规格表中的完整大小 */
    private record Candidate(String formatId, String filename, String url,
                             int width, int height, long fullSize) {
    }
}
