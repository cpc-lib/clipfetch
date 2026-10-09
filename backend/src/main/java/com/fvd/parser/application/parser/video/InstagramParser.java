package com.fvd.parser.application.parser.video;

import com.fvd.parser.application.ParseContext;
import com.fvd.parser.application.parser.AbstractVideoParser;
import com.fvd.parser.application.parser.CookiePolicy;
import com.fvd.parser.infrastructure.service.YtDlpService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.BusinessException;
import com.fvd.parser.domain.FormatInfo;
import com.fvd.parser.domain.MediaItem;
import com.fvd.parser.domain.Platform;
import com.fvd.parser.domain.VideoInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Instagram 图文/视频帖解析（纯 Java 实现）。
 * 数据来源：帖子页内嵌的 data-sjs JSON 块（RelayPrefetchedStreamCache 预取数据），
 * 以"浏览器导航请求"头（Sec-Fetch-Mode: navigate）访问即可获得完整轮播数据，无需登录。
 * 下载：服务端经代理（带 Referer）拉取 CDN 资源，多图打包 ZIP。
 */
@Slf4j
@Service
public class InstagramParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.instagram.com/";
    private static final String IMAGES_FORMAT_ID = "images";
    private static final Pattern SHORTCODE_RE =
            Pattern.compile("instagram\\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)");
    /**
     * 页面内嵌的 SJS 数据流（Instagram RelayPrefetchedStreamCache）
     */
    private static final Pattern SJS_RE =
            Pattern.compile("(?s)<script\\b[^>]+\\bdata-sjs>(\\{.*?\\})</script>");

    private final String proxy;
    private final int parseTimeout;
    private final String ffmpegLocation;
    private final YtDlpService ytDlp;
    private final CookieService cookieService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 直链时效较短，解析结果缓存 10 分钟供下载使用（按用户隔离）
     */
    private final Map<String, CachedPost> cache = new ConcurrentHashMap<>();

    public InstagramParser(@Value("${app.proxy:}") String proxy,
                           @Value("${app.parse-timeout:60}") int parseTimeout,
                           @Value("${app.ffmpeg-location:}") String ffmpegLocation,
                           YtDlpService ytDlp,
                           CookieService cookieService) {
        this.proxy = proxy;
        this.parseTimeout = parseTimeout;
        this.ffmpegLocation = ffmpegLocation;
        this.ytDlp = ytDlp;
        this.cookieService = cookieService;
    }

    @Override
    public Platform platform() {
        return Platform.INSTAGRAM;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.OPTIONAL;
    }

    private static Integer intOrNull(JsonNode n) {
        int v = n.asInt(0);
        return v > 0 ? v : null;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        Long userId = ctx.user() != null ? ctx.user().getId() : null;
        String cookieContent = ctx.cookies();
        try {
            return parsePost(url, userId, cookieContent);
        } catch (LoginRequiredException e) {
            cookieService.markInvalid(ctx.user(), Platform.INSTAGRAM, e.getMessage());
            throw e;
        } catch (BusinessException e) {
            cookieService.markInvalidIfAuth(ctx.user(), Platform.INSTAGRAM, e.getMessage());
            return ytDlp.parse(url, cookieContent);
        }
    }

    /**
     * @param userId        当前登录用户 id（解析缓存按用户隔离）
     * @param cookieContent 用户上传的 cookies.txt 原文（非空）
     */
    public VideoInfo parsePost(String url, Long userId, String cookieContent) {
        JsonNode post = fetchPost(url, userId, cookieContent);
        String title = post.path("title").asText("Instagram 帖子");
        String uploader = post.path("uploader").asText(null);
        String thumbnail = post.path("thumbnail").asText(null);
        List<JsonNode> media = collect(post);
        long images = media.stream().filter(m -> "image".equals(m.path("type").asText())).count();
        long videos = media.size() - images;
        String label = media.size() > 1
                ? "全部内容（" + media.size() + " 项" + (images > 0 ? "，图片 " + images : "")
                + (videos > 0 ? "，视频 " + videos : "") + "，ZIP）"
                : ("image".equals(media.get(0).path("type").asText())
                ? "原图 " + extFromUrl(media.get(0).path("url").asText()).toUpperCase() : "视频 MP4");
        FormatInfo format = new FormatInfo(
                IMAGES_FORMAT_ID,
                media.size() > 1 ? "zip"
                        : "image".equals(media.get(0).path("type").asText())
                        ? extFromUrl(media.get(0).path("url").asText()) : "mp4",
                null, null, null, null, null, null, label, false, false, true);
        List<MediaItem> mediaList = media.stream()
                .map(m -> new MediaItem(
                        m.path("type").asText(),
                        m.path("url").asText(null),
                        m.path("cover").asText(null),
                        intOrNull(m.path("width")),
                        intOrNull(m.path("height"))))
                .toList();
        return new VideoInfo(null, title, thumbnail, null, null, uploader,
                Platform.INSTAGRAM.display, null, null, List.of(format), mediaList, List.of(), false);
    }

    // ===== 解析（纯 Java：页面 + data-sjs） =====

    /**
     * 下载：单项直接流式回写；多项打包 ZIP
     */
    public void download(String url, String title, HttpServletResponse response,
                         Long userId, String cookieContent) {
        JsonNode post = fetchPost(url, userId, cookieContent);
        List<JsonNode> media = collect(post);
        String baseName = sanitizeTitle(title != null ? title : post.path("title").asText("instagram"));
        try {
            if (media.size() == 1) {
                streamSingle(media.get(0), baseName, response);
            } else {
                streamZip(media, baseName, response);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Instagram 内容下载失败：" + e.getMessage());
        }
    }

    private JsonNode fetchPost(String url, Long userId, String cookieContent) {
        String cacheKey = userId + "|" + url;
        CachedPost cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt.isAfter(Instant.now())) {
            return cached.data;
        }
        String shortcode = extractShortcode(url);
        HttpClient client = pageHttpClient();
        String pageUrl = "https://www.instagram.com/p/" + shortcode + "/";
        // JDK CookieManager 会发 RFC2965 风格 Cookie 头（带 $Version/$Domain 与引号），
        // Instagram 的 WAF 无法识别，故手动构造经典 name=value 形式
        String cookieHeader = CookieService.toCookieHeader(cookieContent, Platform.INSTAGRAM);

        ObjectNode product = null;
        Exception lastError = null;
        boolean gated = false;
        // Instagram 偶发下发无数据骨架页（限流），重试 3 次；登录门控则立即失败
        for (int attempt = 1; attempt <= 3 && product == null; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(pageUrl))
                        .timeout(Duration.ofSeconds(parseTimeout))
                        .header("User-Agent", UA)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-us,en;q=0.5")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Site", "none")
                        .header("Upgrade-Insecure-Requests", "1");
                if (!cookieHeader.isEmpty()) {
                    rb.header("Cookie", cookieHeader);
                }
                HttpRequest req = rb.GET().build();
                HttpResponse<String> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() >= 300 && resp.statusCode() < 400) {
                    // cookies 失效/伪造时 Instagram 直接 302 跳登录页（0 字节），判定为登录门控
                    gated = true;
                    break;
                }
                if (resp.statusCode() != 200) {
                    throw new BusinessException("Instagram 页面返回状态码 " + resp.statusCode());
                }
                Extract extract = extractProduct(resp.body(), shortcode);
                if (extract.gated()) {
                    gated = true;
                    break;
                }
                product = extract.product();
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("Instagram 页面解析第 {} 次失败: {}", attempt, e.getMessage());
            }
            if (product == null && !gated && attempt < 3) {
                try {
                    Thread.sleep(4000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("解析被中断");
                }
            }
        }
        if (gated) {
            throw new LoginRequiredException(cookieHeader.isEmpty()
                    ? "尚未配置 Instagram cookies，请在「我的 Cookies」中上传"
                    : "Instagram cookies 已失效（需要重新登录），请在「我的 Cookies」中重新上传");
        }
        if (product == null) {
            throw new BusinessException("无法获取帖子数据（可能为私密帖、需要登录或触发了限流，请稍后重试）"
                    + (lastError != null ? "：" + lastError.getMessage() : ""));
        }

        ObjectNode result = mapPost(product);
        if (collect(result).isEmpty()) {
            throw new BusinessException("该帖子没有可下载的图片或视频");
        }
        ensureThumbnail(result);
        cache.put(cacheKey, new CachedPost(result, Instant.now().plusSeconds(600)));
        return result;
    }

    /**
     * 从页面所有 data-sjs 块中递归定位媒体数据（匿名 polaris 通道 / 登录态 web_info、clips 通道）
     */
    private Extract extractProduct(String html, String shortcode) {
        Matcher matcher = SJS_RE.matcher(html);
        boolean gatedSeen = false;
        while (matcher.find()) {
            try {
                JsonNode root = mapper.readTree(matcher.group(1));
                JsonNode polaris = findPolaris(root);
                if (polaris != null) {
                    JsonNode p = polaris.get("if_not_gated_logged_out");
                    if (p != null && p.isObject()) {
                        return new Extract((ObjectNode) p, false);
                    }
                    // 节点存在但为 null 或不含匿名数据：Instagram 对未登录用户做了门控
                    gatedSeen = true;
                }
                // 登录态：xdt_api__v1__media__shortcode__web_info.items[] /
                // clips feed edges[].node.media，按帖子 code 精确匹配
                ObjectNode loggedIn = findMediaByCode(root, shortcode);
                if (loggedIn != null) {
                    return new Extract(loggedIn, false);
                }
            } catch (Exception ignored) {
                // 非 JSON 或结构不符的块跳过
            }
        }
        return new Extract(null, gatedSeen);
    }

    /**
     * 递归查找 code 等于帖子 shortcode 且含媒体字段的对象（轮播子项 code 不同，不会误匹配）
     */
    private ObjectNode findMediaByCode(JsonNode node, String code) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            if (code.equals(node.path("code").asText()) && looksLikeMedia(node)) {
                return (ObjectNode) node;
            }
            for (JsonNode child : node) {
                ObjectNode found = findMediaByCode(child, code);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                ObjectNode found = findMediaByCode(child, code);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean looksLikeMedia(JsonNode node) {
        return node.has("video_versions") || node.has("image_versions2") || node.has("carousel_media");
    }

    private JsonNode findPolaris(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            // 门控时该键存在但值为 JSON null（has 对显式 null 也成立）
            if (node.has("xig_polaris_media")) {
                return node.get("xig_polaris_media");
            }
            for (JsonNode child : node) {
                JsonNode found = findPolaris(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findPolaris(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 将 IG 原始 product 归一化为内部结构：title/uploader/thumbnail/media[]
     */
    private ObjectNode mapPost(ObjectNode p) {
        ObjectNode result = mapper.createObjectNode();
        JsonNode user = p.path("user");
        String username = user.path("username").asText("");
        String fullName = user.path("full_name").asText("");
        String caption = p.path("caption").path("text").asText("");
        result.put("title", !caption.isBlank() ? caption : ("Post by " + username));
        result.put("uploader", !fullName.isBlank() ? fullName : username);
        result.put("username", username);
        result.put("taken_at", p.path("taken_at").asLong(0));

        List<JsonNode> children = new ArrayList<>();
        JsonNode carousel = p.get("carousel_media");
        if (carousel != null && carousel.isArray() && !carousel.isEmpty()) {
            carousel.forEach(children::add);
        } else {
            children.add(p);
        }
        ArrayNode mediaArr = result.putArray("media");
        for (JsonNode child : children) {
            ObjectNode m = pickMedia(child);
            if (m != null) {
                mediaArr.add(m);
            }
        }
        if (!mediaArr.isEmpty()) {
            JsonNode first = mediaArr.get(0);
            // 视频项的 url 是 mp4 不能作 <img> 封面，须用 image_versions2 封面帧
            String thumb = "video".equals(first.path("type").asText())
                    ? first.path("cover").asText(null)
                    : first.path("url").asText(null);
            if (thumb != null && !thumb.isBlank()) {
                result.put("thumbnail", thumb);
            }
        }
        return result;
    }

    private ObjectNode pickMedia(JsonNode item) {
        // 视频帖：取分辨率最高的 video_versions
        JsonNode videos = item.get("video_versions");
        if (videos != null && videos.isArray() && !videos.isEmpty()) {
            JsonNode best = null;
            int bestArea = -1;
            for (JsonNode v : videos) {
                int area = v.path("width").asInt(0) * v.path("height").asInt(0);
                if (area > bestArea) {
                    bestArea = area;
                    best = v;
                }
            }
            if (best != null && best.hasNonNull("url")) {
                ObjectNode m = mapper.createObjectNode();
                m.put("type", "video");
                m.put("url", best.get("url").asText());
                m.put("width", best.path("width").asInt(0));
                m.put("height", best.path("height").asInt(0));
                // 封面帧：IG 为视频自带的 image_versions2（即视频首帧/封面图）
                String cover = firstImageUrl(item);
                if (cover != null) {
                    m.put("cover", cover);
                }
                return m;
            }
        }
        // 图片帖：candidates 首个为无变换原图
        JsonNode candidates = item.path("image_versions2").path("candidates");
        if (candidates.isArray() && !candidates.isEmpty() && candidates.get(0).hasNonNull("url")) {
            JsonNode c = candidates.get(0);
            ObjectNode m = mapper.createObjectNode();
            m.put("type", "image");
            m.put("url", c.get("url").asText());
            m.put("width", c.path("width").asInt(0));
            m.put("height", c.path("height").asInt(0));
            return m;
        }
        return null;
    }

    /**
     * image_versions2.candidates 中首个带 url 的项（IG 生成的视频封面帧）
     */
    private String firstImageUrl(JsonNode item) {
        for (JsonNode c : item.path("image_versions2").path("candidates")) {
            if (c.hasNonNull("url")) {
                return c.get("url").asText();
            }
        }
        return null;
    }

    /**
     * 封面兜底：IG 未提供视频封面（无 image_versions2）时，
     * 用 ffmpeg 从视频直链抽取第一帧作为封面（JPEG data URI，直接内嵌解析结果）
     */
    private void ensureThumbnail(ObjectNode result) {
        String thumb = result.path("thumbnail").asText(null);
        if (thumb != null && !thumb.isBlank()) {
            return;
        }
        JsonNode first = result.path("media").path(0);
        if (first.isMissingNode() || !"video".equals(first.path("type").asText())) {
            return;
        }
        String frame = extractFirstFrame(first.path("url").asText());
        if (frame != null) {
            result.put("thumbnail", frame);
        }
    }

    /**
     * ffmpeg 抽取视频第一帧，返回 data:image/jpeg;base64 或 null（失败不阻塞解析）
     */
    private String extractFirstFrame(String videoUrl) {
        String ffmpeg = resolveFfmpeg();
        if (ffmpeg == null) {
            return null;
        }
        Path out = null;
        try {
            out = Files.createTempFile("fvd-frame-", ".jpg");
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                    "-user_agent", UA));
            if (proxy != null && !proxy.isBlank()) {
                cmd.add("-proxy");
                cmd.add(proxy);
            }
            cmd.addAll(List.of("-headers", "Referer: " + REFERER + "\r\n",
                    "-i", videoUrl, "-frames:v", "1", "-q:v", "4", "-y", out.toString()));
            Process proc = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!proc.waitFor(45, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() != 0) {
                return null;
            }
            byte[] data = Files.readAllBytes(out);
            if (data.length == 0) {
                return null;
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            log.warn("抽取视频首帧失败: {}", e.getMessage());
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String resolveFfmpeg() {
        if (ffmpegLocation == null || ffmpegLocation.isBlank()) {
            return "ffmpeg"; // 取 PATH
        }
        Path p = Path.of(ffmpegLocation);
        if (Files.isRegularFile(p)) {
            return p.toString();
        }
        if (Files.isDirectory(p)) {
            Path exe = p.resolve("ffmpeg.exe");
            if (Files.isRegularFile(exe)) {
                return exe.toString();
            }
        }
        log.warn("ffmpeg 路径无效，跳过首帧封面兜底: {}", ffmpegLocation);
        return null;
    }

    private HttpClient pageHttpClient() {
        // 不挂 CookieHandler：Cookie 头手动构造（见 fetchPost），避免 JDK 追加 RFC2965 风格头；
        // 不跟随重定向：cookies 失效时 IG 302 跳登录，调用方据此判定登录态失效
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15));
        configureProxy(builder);
        return builder.build();
    }

    private String extractShortcode(String url) {
        Matcher m = SHORTCODE_RE.matcher(url);
        if (!m.find()) {
            throw new BusinessException("无法识别 Instagram 帖子链接");
        }
        return m.group(1);
    }

    // ===== 下载实现 =====

    private void streamSingle(JsonNode item, String baseName, HttpServletResponse response) throws Exception {
        String mediaUrl = item.path("url").asText();
        java.net.http.HttpClient client = mediaHttpClient();
        HttpResponse<InputStream> resp = fetchMedia(client, mediaUrl);
        String ext = extFromResponse(resp, "image".equals(item.path("type").asText()) ? "jpg" : "mp4");
        byte[] first = resp.body().readNBytes(12);
        ext = extFromMagic(first, ext);

        String filename = baseName + "." + ext;
        response.setContentType("image".equals(item.path("type").asText()) ? imageContentType(ext) : "video/mp4");
        response.setHeader("Content-Disposition", disposition(filename));
        response.setHeader("Cache-Control", "no-store");
        try (InputStream in = resp.body(); var out = response.getOutputStream()) {
            out.write(first);
            in.transferTo(out);
            out.flush();
        }
    }

    private void streamZip(List<JsonNode> media, String baseName, HttpServletResponse response) throws Exception {
        String filename = baseName + ".zip";
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", disposition(filename));
        response.setHeader("Cache-Control", "no-store");
        java.net.http.HttpClient client = mediaHttpClient();
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
            int idx = 1;
            for (JsonNode item : media) {
                String fallbackExt = "image".equals(item.path("type").asText()) ? "jpg" : "mp4";
                HttpResponse<InputStream> resp = fetchMedia(client, item.path("url").asText());
                byte[] head = resp.body().readNBytes(12);
                String ext = extFromMagic(head, extFromResponse(resp, fallbackExt));
                zip.putNextEntry(new ZipEntry(idx++ + "." + ext));
                zip.write(head);
                try (InputStream in = resp.body()) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
            zip.finish();
            zip.flush();
        }
    }

    private HttpResponse<InputStream> fetchMedia(java.net.http.HttpClient client, String mediaUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(mediaUrl))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "image/*,video/*,*/*;q=0.8")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<InputStream> resp =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new BusinessException("Instagram 资源返回状态码 " + resp.statusCode());
        }
        return resp;
    }

    private java.net.http.HttpClient mediaHttpClient() {
        java.net.http.HttpClient.Builder b = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        configureProxy(b);
        return b.build();
    }

    private void configureProxy(HttpClient.Builder builder) {
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI pu = URI.create(proxy);
                builder.proxy(ProxySelector.of(new InetSocketAddress(pu.getHost(), pu.getPort())));
            } catch (Exception e) {
                log.warn("代理地址无效，直连请求：{}", proxy);
            }
        }
    }

    private List<JsonNode> collect(JsonNode post) {
        List<JsonNode> list = new ArrayList<>();
        post.path("media").forEach(list::add);
        return list;
    }

    // ===== 工具 =====

    /**
     * 从 CDN URL 路径段取图片扩展名（IG 图文实际多为 webp）
     */
    private String extFromUrl(String url) {
        String path = URI.create(url).getPath().toLowerCase();
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String ext = path.substring(dot + 1);
            if (ext.matches("[a-z0-9]{2,4}")) {
                return ext;
            }
        }
        return "jpg";
    }

    private String extFromResponse(HttpResponse<InputStream> resp, String fallback) {
        String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (ct.contains("webp")) {
            return "webp";
        }
        if (ct.contains("png")) {
            return "png";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return "jpg";
        }
        if (ct.contains("mp4")) {
            return "mp4";
        }
        return fallback;
    }

    /**
     * CDN 实际返回格式可能与 URL 后缀不一致，用文件头兜底
     */
    private String extFromMagic(byte[] head, String fallback) {
        if (head.length >= 4) {
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
                return "jpg";
            }
            if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
                return "webp";
            }
            if (head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "png";
            }
        }
        return fallback;
    }

    private String imageContentType(String ext) {
        return switch (ext) {
            case "webp" -> "image/webp";
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }

    private String disposition(String filename) {
        return "attachment; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sanitizeTitle(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        return cleaned.isEmpty() ? "instagram" : cleaned;
    }

    private record Extract(ObjectNode product, boolean gated) {
    }

    private record CachedPost(JsonNode data, Instant expireAt) {
    }

    /**
     * 帖子被登录门控：此时回退 yt-dlp 同样无法访问，不应再尝试
     */
    public static class LoginRequiredException extends BusinessException {
        public LoginRequiredException(String message) {
            super(message);
        }
    }
}
