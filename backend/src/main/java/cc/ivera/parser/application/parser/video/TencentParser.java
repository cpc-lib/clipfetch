package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.infrastructure.service.YtDlpService;
import cc.ivera.parser.infrastructure.sidecar.TencentBrowserSidecar;

import com.fasterxml.jackson.databind.JsonNode;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.application.DownloadService;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯视频解析：sidecar 驱动真实播放器逐档切换清晰度，拦截官方 CDN 分片地址，
 * 反推出每档 m3u8 直链（含 VIP 1080P / SVIP 4K，yt-dlp 自身只能拿到 480P/720P）。
 *
 * <p>流程：
 * 1. 登录：sidecar 弹出有头 Chrome，用户手动登录，登录态持久化在 profile 目录
 * 2. 解析：sidecar 播放页面并切换清晰度，返回各档名称与官方 CDN m3u8 URL；yt-dlp 兜底
 * 3. 下载：yt-dlp 带 Referer 直接下载选中档的 m3u8（vipts.tc.qq.com，无水印）
 */
@Slf4j
@Service
public class TencentParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String BAIDU_SPIDER_UA = "Baiduspider/2.0";
    private static final Pattern VID_IN_PATH = Pattern.compile("/([a-zA-Z0-9]+)\\.html");
    private static final Pattern VID_IN_QUERY = Pattern.compile("[?&]vid=([a-zA-Z0-9]+)");
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile(
            "<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    /** sidecar 返回的直链有效期保守取 3 小时，过期后下载时重新解析。 */
    private static final long URL_TTL_MS = 3 * 60 * 60 * 1000L;

    /** defn key -> 像素高 */
    private static final java.util.Map<String, Integer> DEFN_HEIGHT = java.util.Map.of(
            "480p", 480,
            "720p", 720,
            "1080p", 1080,
            "zhencai_1080", 1080,
            "4k", 2160,
            "zhencai_max_4k60", 2160);

    private final TencentBrowserSidecar sidecar;
    private final DownloadService downloadService;
    private final YtDlpService ytDlp;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** vid -> 各档直链缓存（sidecar 解析结果，供下载时按 formatId 取用）。 */
    private final java.util.Map<String, ResolvedUrls> resolvedCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record ResolvedUrls(java.util.Map<String, String> urls, long expireAt) {
    }

    public TencentParser(TencentBrowserSidecar sidecar, DownloadService downloadService, YtDlpService ytDlp) {
        this.sidecar = sidecar;
        this.downloadService = downloadService;
        this.ytDlp = ytDlp;
    }

    @Override
    public Platform platform() {
        return Platform.TENCENT;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String vid = extractVid(url);
        try {
            return parseViaSidecar(url, vid);
        } catch (Exception e) {
            log.warn("腾讯视频 sidecar 解析失败，回退 yt-dlp: {}", e.getMessage());
            return parseViaYtDlp(url, vid);
        }
    }

    /** sidecar 真实播放器取流：可拿到 VIP/SVIP 全部清晰度。 */
    private VideoInfo parseViaSidecar(String url, String vid) {
        JsonNode node = sidecar.resolve(url);
        // 旧版 sidecar 无此字段时默认已登录，避免误伤正常解析
        boolean loggedIn = node.path("logged_in").asBoolean(true);
        String title = node.path("title").asText("").trim();
        if (title.isBlank()) title = "腾讯视频 " + vid;
        long duration = node.path("duration").asLong(0);
        String cover = fetchCover(url);

        List<FormatInfo> formatInfos = new ArrayList<>();
        java.util.Map<String, String> urls = new java.util.LinkedHashMap<>();
        for (JsonNode f : node.path("formats")) {
            String defn = f.path("defn").asText("");
            String name = f.path("name").asText(defn);
            String streamUrl = f.path("url").asText("");
            if (defn.isBlank() || streamUrl.isBlank()) continue;
            int height = DEFN_HEIGHT.getOrDefault(defn, 0);
            formatInfos.add(new FormatInfo(
                    defn, "mp4", height > 0 ? height + "P" : name,
                    height > 0 ? height : null, null, null, null, null,
                    name, false, false, true));
            urls.put(defn, streamUrl);
        }
        if (formatInfos.isEmpty()) {
            throw new BusinessException("播放器未返回任何清晰度，可能需要登录或该影片暂不支持。");
        }
        resolvedCache.put(vid, new ResolvedUrls(urls, System.currentTimeMillis() + URL_TTL_MS));

        // 短时长（<2分钟）是「只拿到预告片」的典型特征：不阻断（真实短视频合法），
        // 通过 notice 让前端明确提示，未登录时引导扫码。
        // 注意不能限制单档——未登录时预告片本身也可能切出全部清晰度档位。
        String notice = null;
        if (duration > 0 && duration < 120) {
            notice = loggedIn
                    ? "当前视频时长极短（" + duration + "秒），可能是预告片或付费片段；若非预告片，请忽略此提示。"
                    : "未登录腾讯视频，当前仅获取到 " + duration + " 秒预告片内容；扫码登录后可解析正片全部清晰度。";
        }
        return new VideoInfo(
                vid, title, cover,
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                null, Platform.TENCENT.display, null, null,
                formatInfos, null, List.of(), false, notice);
    }

    /** yt-dlp 兜底：仅 480P/720P（高档 cKey 无法生成），同高度的多 CDN 节点去重。 */
    private VideoInfo parseViaYtDlp(String url, String vid) {
        JsonNode info = dumpJson(url);
        String title = info.path("title").asText("");
        if (title.isBlank() || title.startsWith("[vqq:video]") || title.startsWith("vqq-video")) {
            title = fetchTitle(url);
            if (title.isBlank()) title = "腾讯视频 " + vid;
        }
        long duration = info.path("duration").asLong(0);
        String cover = info.path("thumbnail").asText("");
        if (cover.isBlank()) cover = fetchCover(url);

        List<FormatInfo> formatInfos = new ArrayList<>();
        java.util.Set<Integer> seenHeights = new java.util.HashSet<>();
        for (JsonNode fmt : info.path("formats")) {
            String fid = fmt.path("format_id").asText("");
            if (fid.isBlank()) continue;
            int height = fmt.path("height").asInt(0);
            if (height > 0 && !seenHeights.add(height)) continue;   // 同清晰度多 CDN 只保留一档
            String ext = fmt.path("ext").asText("mp4");
            String note = fmt.path("format_note").asText("");
            int width = fmt.path("width").asInt(0);
            String resolution = width > 0 && height > 0 ? width + "x" + height
                    : (note.isBlank() ? fid : note);
            formatInfos.add(new FormatInfo(
                    fid, ext, resolution, height > 0 ? height : null,
                    null, null,
                    fmt.path("vcodec").asText(null), fmt.path("acodec").asText(null),
                    note.isBlank() ? fid : note, false, false, true));
        }
        formatInfos.sort(java.util.Comparator.comparingInt(
                f -> f.height() != null ? f.height() : Integer.MAX_VALUE));
        if (formatInfos.isEmpty()) {
            throw new BusinessException("未获取到可播放的视频流，可能是 VIP 内容需登录。"
                    + "请在「Cookies」弹窗中点击腾讯视频「扫码登录」后重试。");
        }

        return new VideoInfo(
                vid, title, cover,
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                null, Platform.TENCENT.display, null, null,
                formatInfos, null, List.of(), false);
    }

    /**
     * 下载：优先用 sidecar 缓存的该档官方 CDN m3u8 直链；兜底 yt-dlp + cookies。
     */
    public void download(String url, String formatId, String title,
                         HttpServletResponse response, String taskId) {
        String name = title != null && !title.isBlank() ? title : "tencent-" + extractVid(url);
        String direct = directUrl(url, formatId);
        if (direct != null) {
            List<String> extra = new ArrayList<>();
            extra.add("--add-headers");
            extra.add("Referer:https://v.qq.com/");
            extra.add("--add-headers");
            extra.add("User-Agent:" + UA);
            downloadService.downloadToResponse(direct, null, name, response, null, taskId, extra);
            return;
        }
        List<String> extra = new ArrayList<>(cookieArgs());
        extra.add("--add-headers");
        extra.add("Referer:https://v.qq.com/");
        downloadService.downloadToResponse(url, formatId, name, response, null, taskId, extra);
    }

    /** 取某档 sidecar 直链；缓存缺失或过期时重新解析一次。 */
    private String directUrl(String pageUrl, String formatId) {
        String vid = extractVid(pageUrl);
        ResolvedUrls cached = resolvedCache.get(vid);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()
                && cached.urls().containsKey(formatId)) {
            return cached.urls().get(formatId);
        }
        try {
            parseViaSidecar(pageUrl, vid);
            ResolvedUrls fresh = resolvedCache.get(vid);
            if (fresh != null) return fresh.urls().get(formatId);
        } catch (Exception e) {
            log.warn("下载前重新解析腾讯视频失败，改用 yt-dlp: {}", e.getMessage());
        }
        return null;
    }

    // ===== 内部实现 =====

    /** yt-dlp --dump-single-json 拿完整视频信息。 */
    private JsonNode dumpJson(String url) {
        try {
            Path out = Files.createTempFile("fvd-tencent-", ".json");
            List<String> cmd = new ArrayList<>();
            cmd.add(ytDlp.ytdlpPath());
            cmd.add("--no-playlist");
            cmd.add("--no-warnings");
            cmd.add("--dump-single-json");
            cmd.add("--socket-timeout");
            cmd.add("30");
            cmd.addAll(cookieArgs());
            cmd.add("-o");
            cmd.add(out.toString());
            cmd.add(url);
            String stdout = ytDlp.execute(cmd, 120);
            return ytDlp.readJson(stdout);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("腾讯视频 yt-dlp 解析失败: {}", e.getMessage());
            throw new BusinessException("腾讯视频解析失败，请确认已扫码登录后重试。");
        }
    }

    /**
     * 构造 yt-dlp cookie 参数：sidecar Chrome 常驻运行时独占锁定 cookie 数据库，
     * {@code --cookies-from-browser} 无法复制（yt-dlp #7271），改由 sidecar 经
     * Playwright API 导出为固定临时 cookies.txt，用 {@code --cookies} 读取。
     * 导出失败时返回空列表（匿名兜底，匿名 HLS 会被限速）。
     */
    private List<String> cookieArgs() {
        String netscape = sidecar.cookiesNetscape();
        if (netscape == null || netscape.isBlank() || !netscape.contains("\t")) {
            return List.of();
        }
        try {
            Path cookieFile = Path.of(System.getProperty("java.io.tmpdir"),
                    "clipfetch-tencent-cookies.txt");
            Files.writeString(cookieFile, netscape, StandardCharsets.UTF_8);
            return List.of("--cookies", cookieFile.toString());
        } catch (Exception e) {
            log.warn("腾讯视频 cookies.txt 写入失败，yt-dlp 以匿名方式请求: {}", e.getMessage());
            return List.of();
        }
    }

    private static String extractVid(String url) {
        Matcher m = VID_IN_QUERY.matcher(url);
        if (m.find()) return m.group(1);
        m = VID_IN_PATH.matcher(url);
        if (m.find()) return m.group(1);
        throw new BusinessException("无法从腾讯视频链接中识别视频 ID");
    }

    /** 用 Baiduspider UA 抓播放页 og:title 作为标题。 */
    private String fetchTitle(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", BAIDU_SPIDER_UA)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher m = OG_TITLE.matcher(resp.body());
            if (m.find()) return m.group(1).trim();
        } catch (Exception e) {
            log.debug("腾讯视频标题获取失败: {}", e.getMessage());
        }
        return "";
    }

    private String fetchCover(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", BAIDU_SPIDER_UA)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher m = OG_IMAGE.matcher(resp.body());
            if (m.find()) {
                String cover = m.group(1);
                if (cover.startsWith("//")) cover = "https:" + cover;
                return cover;
            }
        } catch (Exception e) {
            log.debug("腾讯视频封面获取失败: {}", e.getMessage());
        }
        return null;
    }
}
