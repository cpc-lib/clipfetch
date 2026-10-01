package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.application.DownloadService;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.VideoInfo;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.WaitForSelectorState;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vip.61la.com：Java Playwright 操作线路选择和播放表单，捕获播放器实际请求的 HLS/MP4。
 * 支持腾讯视频 v.qq.com、优酷 v.youku.com、爱奇艺 iqiyi.com、芒果TV mgtv.com。
 * 下载前重新解析签名地址；浏览器会话独立，不使用或转发用户的平台账号 cookies。
 */
@Slf4j
@Service
public class VipParser {

    public static final String FORMAT_ID = "vip_default";
    private static final String SITE_URL = "https://vip.61la.com/";
    private static final List<String> SUPPORTED_HOSTS =
            List.of("v.qq.com", "v.youku.com", "iqiyi.com", "mgtv.com");
    // 线路一播放器页面内嵌 AES-CBC 加密的官方 CDN 直链，可直接 HTTP 解密取流
    private static final String DIRECT_PLAYER_API = "https://bfq.txnp.cn/player?url=";
    private static final Pattern RESULT_PATTERN = Pattern.compile("let result = \"([^\"]+)\"");
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final int parseTimeout;
    private final DownloadService downloadService;
    private final String siteUrl;

    @Autowired
    public VipParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                     DownloadService downloadService) {
        this(parseTimeout, downloadService, SITE_URL);
    }

    VipParser(int parseTimeout, DownloadService downloadService, String siteUrl) {
        this.parseTimeout = parseTimeout;
        this.downloadService = downloadService;
        this.siteUrl = siteUrl;
    }

    public boolean supports(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            for (String domain : SUPPORTED_HOSTS) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public VideoInfo parse(String url) {
        DownloadTarget target = resolveDownload(url);
        Long duration = target.duration();
        String quality = target.qn() != null && !target.qn().isBlank() ? " " + target.qn() : "";
        FormatInfo format = new FormatInfo(FORMAT_ID, "mp4", null, null,
                null, null, null, null, "VIP 解析 · " + target.line() + quality, false, false, true);
        String site = siteName(url);
        String title = target.title() != null && !target.title().isBlank()
                ? target.title() : site + " " + videoId(url);
        return new VideoInfo(videoId(url), title, null, duration,
                duration != null ? YtDlpService.formatDuration(duration) : null,
                null, site, null, null, List.of(format), null, List.of(), false);
    }

    public void download(String url, String formatId, String title,
                         HttpServletResponse response, String taskId) {
        if (formatId != null && !FORMAT_ID.equals(formatId)) {
            throw new BusinessException("VIP 解析格式无效，请重新解析视频");
        }
        DownloadTarget target = resolveDownload(url);
        String name = title != null && !title.isBlank() ? title
                : target.title() != null && !target.title().isBlank() ? target.title()
                : "vip-" + videoId(url);
        downloadService.downloadToResponse(target.url(), null,
                name, response, target.cookies(), taskId, target.ytDlpArgs());
    }

    DownloadTarget resolveDownload(String url) {
        if (!supports(url)) {
            throw new BusinessException("不是支持的视频链接（支持腾讯视频/优酷/爱奇艺/芒果TV）");
        }
        log.info("VIP 开始解析: {}", url);
        // 优先直接 HTTP 解密线路一内嵌的官方源，秒级返回；失败再走浏览器逐线路捕获
        DownloadTarget direct = resolveDirect(url);
        if (direct != null) {
            return direct;
        }
        log.info("VIP 直取未命中，回退浏览器逐线路解析");
        // 不在请求中隐式安装浏览器；部署时用 Playwright CLI 安装 Chromium。
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setTimeout(parseTimeout * 1000.0));
             BrowserContext context = browser.newContext()) {
            return capture(context, url);
        } catch (BusinessException e) {
            throw e;
        } catch (PlaywrightException e) {
            if (e.getMessage() != null && e.getMessage().contains("Executable doesn't exist")) {
                throw new BusinessException("VipParser 缺少 Chromium，请先运行 Playwright CLI install chromium");
            }
            log.warn("VIP 浏览器解析失败: {}", e.getMessage());
            throw new BusinessException("VIP 解析站访问失败或超时，请稍后重试");
        }
    }

    /**
     * 线路一（bfq.txnp.cn）播放器页内嵌 let result = "..."，密文+key+iv 拼接：
     * AES-CBC 密文（base64）= result[:-32]，key = result[len-32:len-16]，iv = result[len-16:]。
     * 解密得 JSON，video_info.video.url 为官方 CDN 流地址（无水印），另含真实标题和清晰度。
     */
    private DownloadTarget resolveDirect(String url) {
        try {
            Map<String, String> headers = Map.of("user-agent", BROWSER_UA,
                    "referer", "https://bfq.txnp.cn/");
            String html = httpGet(DIRECT_PLAYER_API + url, headers);
            Matcher matcher = RESULT_PATTERN.matcher(html);
            if (!matcher.find()) {
                log.debug("VIP 直取失败: 播放器页未找到 result 密文");
                return null;
            }
            String result = matcher.group(1);
            if (result.length() <= 32) {
                log.debug("VIP 直取失败: result 密文长度异常 len={}", result.length());
                return null;
            }
            String key = result.substring(result.length() - 32, result.length() - 16);
            String iv = result.substring(result.length() - 16);
            byte[] encrypted = Base64.getDecoder().decode(result.substring(0, result.length() - 32));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            JsonNode video = OBJECT_MAPPER.readTree(cipher.doFinal(encrypted))
                    .path("video_info").path("video");
            String streamUrl = video.path("url").asText("");
            // 只接受平台官方 CDN 源；否则回退浏览器逐线路捕获
            if (streamUrl.isBlank() || !isOfficialStream(streamUrl, url)) {
                log.debug("VIP 直取失败: 解密流地址非官方源 host={}",
                        streamUrl.isBlank() ? "空" : URI.create(streamUrl).getHost());
                return null;
            }
            Long duration = null;
            try {
                String manifest = httpGet(streamUrl, headers).stripLeading();
                if (manifest.startsWith("#EXTM3U")) {
                    duration = manifestDuration(manifest);
                }
            } catch (Exception e) {
                log.debug("VIP 直取清单读取失败: {}", e.getMessage());
            }
            log.info("VIP 直取命中官方源: {} host={}", video.path("qn").asText(""),
                    URI.create(streamUrl).getHost());
            return new DownloadTarget(streamUrl, headers, null, "线路一", duration,
                    video.path("title").asText(null), video.path("qn").asText(null));
        } catch (Exception e) {
            log.debug("VIP 直取失败: {}", e.getMessage());
            return null;
        }
    }

    private static String httpGet(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private DownloadTarget capture(BrowserContext context, String url) {
        long deadline = System.nanoTime() + parseTimeout * 1_000_000_000L;
        // 第三方中转源常烧录水印，先收满各线路，优先返回平台官方 CDN 的干净流
        DownloadTarget fallback = null;
        try (Page page = context.newPage()) {
            page.setDefaultTimeout(Math.min(15000, remainingMillis(deadline)));
            page.onDialog(Dialog::dismiss);
            page.navigate(siteUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("#jk option").first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED));
            int lines = page.locator("#jk option").count();
            log.info("VIP 浏览器解析: 共 {} 条线路", lines);
            page.locator("#url").fill(url);
            Frame player = page.locator("#palybox").elementHandle().contentFrame();
            if (player == null) {
                throw new BusinessException("VIP 解析站播放器结构已变化");
            }
            for (int i = 0; i < lines && remainingMillis(deadline) > 1; i++) {
                // 均分剩余时间，避免失效的前几条线路耗尽整个解析预算。
                double lineTimeout = remainingMillis(deadline) / (lines - i);
                long lineDeadline = System.nanoTime() + (long) (lineTimeout * 1_000_000);
                page.setDefaultTimeout(Math.max(1, lineTimeout));
                String label = page.locator("#jk option").nth(i).textContent().trim();
                log.info("VIP 浏览器解析: 尝试 {}/{} {}", i + 1, lines, label);
                List<CapturedStream> streams = new ArrayList<>();
                Consumer<Request> onFinished = request -> {
                    Response response = request.response();
                    if (response == null || !isPlayerRequest(request, player)
                            || !"hls".equals(mediaType(response.url(), response.status(),
                            response.headers().getOrDefault("content-type", "")))) {
                        return;
                    }
                    try {
                        String manifest = response.text().stripLeading();
                        if (manifest.startsWith("#EXTM3U")
                                && (manifest.contains("#EXTINF:") || manifest.contains("#EXT-X-STREAM-INF:"))) {
                            streams.add(new CapturedStream(response, manifestDuration(manifest)));
                        }
                    } catch (PlaywrightException e) {
                        log.debug("VIP 线路清单读取失败: {}", label);
                    }
                };
                Consumer<Response> onResponse = response -> {
                    if (isPlayerRequest(response.request(), player)
                            && "mp4".equals(mediaType(response.url(), response.status(),
                            response.headers().getOrDefault("content-type", "")))) {
                        streams.add(new CapturedStream(response, null));
                    }
                };
                page.onRequestFinished(onFinished);
                page.onResponse(onResponse);
                try {
                    page.locator("#jk").selectOption(new SelectOption().setIndex(i));
                    page.locator("button.btn-play").click();
                    // 本线路窗口内优先等待官方 CDN 流，避免拿到第三方水印源就返回
                    page.waitForCondition(() -> hasOfficialStream(streams, url),
                            new Page.WaitForConditionOptions().setTimeout(remainingMillis(lineDeadline)));
                } catch (PlaywrightException e) {
                    log.debug("VIP {} 未等到官方源", label);
                } finally {
                    page.offRequestFinished(onFinished);
                    page.offResponse(onResponse);
                }
                CapturedStream official = firstOfficialStream(streams, url);
                if (official != null) {
                    log.info("VIP 解析命中官方源: {} host={}", label,
                            URI.create(official.response().url()).getHost());
                    return buildTarget(official, label, context);
                }
                if (fallback == null && !streams.isEmpty()) {
                    log.info("VIP {} 仅命中第三方源，暂存为兜底", label);
                    fallback = buildTarget(streams.get(0), label, context);
                }
                // 中止上一线路，下一次监听不会收到旧播放器延迟返回的媒体。
                player.navigate("about:blank", new Frame.NavigateOptions()
                        .setTimeout(Math.max(1, remainingMillis(deadline))));
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new BusinessException("VIP 解析线路均未返回可下载视频，请稍后重试");
    }

    private DownloadTarget buildTarget(CapturedStream stream, String line, BrowserContext context) {
        Map<String, String> headers = stream.response().request().allHeaders();
        return new DownloadTarget(stream.response().url(), headers,
                cookieFile(context.cookies()), line, stream.duration(), null, null);
    }

    private static boolean hasOfficialStream(List<CapturedStream> streams, String pageUrl) {
        return firstOfficialStream(streams, pageUrl) != null;
    }

    private static CapturedStream firstOfficialStream(List<CapturedStream> streams, String pageUrl) {
        for (CapturedStream stream : streams) {
            if (isOfficialStream(stream.response().url(), pageUrl)) {
                return stream;
            }
        }
        return null;
    }

    /**
     * 判断流地址是否托管在平台官方 CDN（官方源无水印，第三方中转源常被烧录水印）。
     */
    private static boolean isOfficialStream(String streamUrl, String pageUrl) {
        try {
            String host = URI.create(streamUrl).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            for (String domain : officialDomains(pageUrl)) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static List<String> officialDomains(String pageUrl) {
        try {
            String host = URI.create(pageUrl).getHost();
            if (host == null) {
                return List.of();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.contains("qq.com")) {
                return List.of("qq.com", "gtimg.com");
            }
            if (host.contains("youku.com")) {
                // cibntv.net 是优酷官方 OTT 流媒体 CDN（valipl*.cp31.ott.cibntv.net，ups 播放链）
                return List.of("youku.com", "youkudns.com", "ykimg.com", "cibntv.net");
            }
            if (host.contains("iqiyi.com")) {
                return List.of("iqiyi.com", "qiyi.com", "iq.com", "71.am");
            }
            if (host.contains("mgtv.com")) {
                return List.of("mgtv.com", "hitv.com");
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private static boolean isPlayerRequest(Request request, Frame player) {
        if (request.isNavigationRequest()) {
            return false;
        }
        try {
            for (Frame frame = request.frame(); frame != null; frame = frame.parentFrame()) {
                if (frame == player) {
                    return true;
                }
            }
        } catch (PlaywrightException ignored) {
            // Service Worker 请求没有所属 frame。
        }
        return false;
    }

    static String mediaType(String url, int status, String contentType) {
        if (status != 200 && status != 206) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                return null;
            }
            String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            if (mime.contains("mpegurl") || (path.endsWith(".m3u8")
                    && (mime.isEmpty() || mime.equals("text/plain") || mime.equals("application/octet-stream")))) {
                return "hls";
            }
            if (mime.equals("video/mp4") && !path.endsWith(".m4s")
                    || path.endsWith(".mp4") && (mime.isEmpty() || mime.equals("application/octet-stream"))) {
                return "mp4";
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    static Long manifestDuration(String manifest) {
        if (!manifest.contains("#EXT-X-ENDLIST")) {
            return null;
        }
        double duration = 0;
        for (String line : manifest.split("\\R")) {
            if (line.startsWith("#EXTINF:")) {
                try {
                    duration += Double.parseDouble(line.substring(8).split(",", 2)[0]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return Double.isFinite(duration) && duration > 0 ? Math.round(duration) : null;
    }

    static String cookieFile(List<Cookie> cookies) {
        if (cookies.isEmpty()) {
            return null;
        }
        StringBuilder result = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (Cookie cookie : cookies) {
            result.append(Boolean.TRUE.equals(cookie.httpOnly) ? "#HttpOnly_" : "")
                    .append(cookie.domain).append('\t')
                    .append(cookie.domain.startsWith(".") ? "TRUE" : "FALSE").append('\t')
                    .append(cookie.path).append('\t')
                    .append(Boolean.TRUE.equals(cookie.secure) ? "TRUE" : "FALSE").append('\t')
                    .append(cookie.expires != null && cookie.expires > 0 ? cookie.expires.longValue() : 0)
                    .append('\t').append(cookie.name).append('\t').append(cookie.value).append('\n');
        }
        return result.toString();
    }

    private static double remainingMillis(long deadline) {
        return Math.max(1, (deadline - System.nanoTime()) / 1_000_000.0);
    }

    private static String siteName(String url) {
        String host = URI.create(url).getHost();
        if (host == null) {
            return "视频";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.contains("qq.com")) {
            return "腾讯视频";
        }
        if (host.contains("youku.com")) {
            return "优酷";
        }
        if (host.contains("iqiyi.com")) {
            return "爱奇艺";
        }
        if (host.contains("mgtv.com")) {
            return "芒果TV";
        }
        return "视频";
    }

    private static String videoId(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        if (path.endsWith(".html")) {
            return path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
        }
        if (uri.getQuery() != null) {
            for (String part : uri.getQuery().split("&")) {
                if (part.startsWith("vid=")) {
                    return part.substring(4);
                }
            }
        }
        return "video";
    }

    private record CapturedStream(Response response, Long duration) {}

    record DownloadTarget(String url, Map<String, String> headers, String cookies, String line, Long duration,
                          String title, String qn) {
        List<String> ytDlpArgs() {
            List<String> args = new ArrayList<>(List.of("-N", "8", "--remux-video", "mp4",
                    "--merge-output-format", "mp4"));
            for (String header : List.of("referer", "user-agent", "origin")) {
                String value = headers.get(header);
                if (value != null && !value.isBlank()) {
                    args.add("--add-header");
                    args.add(header + ":" + value);
                }
            }
            return args;
        }
    }
}
