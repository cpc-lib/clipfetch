package com.fvd.parser.application.parser.video;

import com.fvd.parser.application.ParseContext;
import com.fvd.parser.application.parser.AbstractVideoParser;
import com.fvd.parser.application.parser.CookiePolicy;
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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 微博图文/视频帖解析（纯 Java 实现）。
 * 数据来源：移动端公开接口 https://m.weibo.cn/statuses/show?id={mid}，
 * 以移动端 UA 匿名即可获取公开帖完整数据（text/pics/page_info），无需登录；
 * 被限流/私密帖时可上传微博 cookies（SUB 等）作为可选登录态。
 * 下载：服务端直连 sinaimg CDN（Referer: m.weibo.cn），单项流式回写，多项打包 ZIP。
 * 直链时效较长，但解析结果仍缓存 10 分钟供下载使用。
 */
@Slf4j
@Service
public class WeiboParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String REFERER = "https://m.weibo.cn/";
    private static final String API = "https://m.weibo.cn/statuses/show?id=";
    public static final String FORMAT_ID = "images";

    /**
     * 匹配 /detail/{mid} 或 /status/{mid}
     */
    private static final Pattern DETAIL_RE =
            Pattern.compile("/(?:detail|status)/([A-Za-z0-9]+)");
    /**
     * 匹配 /{uid}/{mid} 形式（uid 为纯数字，mid 为字母数字混合的 base62 串）
     */
    private static final Pattern UID_MID_RE =
            Pattern.compile("/(\\d+)/([A-Za-z0-9]+)(?:[?#/]|$)");

    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");
    private static final Pattern SPACE_RE = Pattern.compile("[ \\t]+");
    private static final DateTimeFormatter WB_DATE =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", java.util.Locale.ENGLISH);

    private final int parseTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedPost> cache = new ConcurrentHashMap<>();

    public WeiboParser(@Value("${app.parse-timeout:60}") int parseTimeout) {
        this.parseTimeout = parseTimeout;
    }

    @Override
    public Platform platform() {
        return Platform.WEIBO;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.OPTIONAL;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String cookieContent = ctx.cookies();
        JsonNode post = fetchPost(url, cookieContent);
        List<JsonNode> media = collect(post);
        if (media.isEmpty()) {
            throw new BusinessException("该微博没有可下载的图片或视频");
        }
        long images = media.stream().filter(m -> "image".equals(m.path("type").asText())).count();
        long videos = media.size() - images;
        String label;
        if (media.size() > 1) {
            List<String> parts = new ArrayList<>();
            if (images > 0) parts.add("图片 " + images);
            if (videos > 0) parts.add("视频 " + videos);
            label = "全部内容（" + media.size() + " 个文件，" + String.join("，", parts) + "，ZIP）";
        } else {
            label = "image".equals(media.get(0).path("type").asText())
                    ? "原图 " + extFromUrl(media.get(0).path("url").asText()).toUpperCase() : "视频 MP4";
        }
        FormatInfo format = new FormatInfo(
                FORMAT_ID,
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
        Long duration = post.path("duration").asLong(0) > 0 ? post.path("duration").asLong() : null;
        return new VideoInfo(null,
                post.path("title").asText("微博"),
                post.path("thumbnail").asText(null),
                duration,
                post.path("durationString").asText(null),
                post.path("uploader").asText(null),
                Platform.WEIBO.display,
                null,
                post.path("uploadDate").asText(null),
                List.of(format), mediaList, List.of(), false,
                post.path("description").asText(null));
    }

    public void download(String url, String title, HttpServletResponse response, String cookieContent) {
        JsonNode post = fetchPost(url, cookieContent);
        List<JsonNode> media = collect(post);
        String baseName = sanitizeTitle(title != null ? title : post.path("title").asText("weibo"));
        try {
            if (media.size() == 1) {
                streamSingle(media.get(0), baseName, response);
            } else {
                streamZip(media, baseName, response);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("微博内容下载失败：" + e.getMessage());
        }
    }

    // ===== 解析（移动端 statuses/show 接口） =====

    private JsonNode fetchPost(String url, String cookieContent) {
        String mid = extractMid(url);
        CachedPost cached = cache.get(mid);
        if (cached != null && cached.expireAt.isAfter(Instant.now())) {
            return cached.data;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(API + mid))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", REFERER)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .GET();
            String cookieHeader = CookieService.toCookieHeader(cookieContent, Platform.WEIBO);
            if (!cookieHeader.isEmpty()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpResponse<String> resp = client.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("微博接口返回状态码 " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root.path("ok").asInt(0) != 1) {
                String msg = root.path("msg").asText("");
                if (msg.contains("登录") || msg.contains("login") || root.path("errno").asInt() == -100) {
                    throw new BusinessException(cookieHeader.isEmpty()
                            ? "该微博需要登录后才能查看，请在「我的 Cookies」中上传微博 cookies"
                            : "微博 cookies 已失效，请重新上传");
                }
                throw new BusinessException("微博接口返回异常" + (msg.isBlank() ? "" : "：" + msg));
            }
            JsonNode status = root.get("data");
            if (status == null || status.isMissingNode()) {
                throw new BusinessException("微博接口未返回数据");
            }
            ObjectNode result = mapPost(status);
            if (collect(result).isEmpty()) {
                throw new BusinessException("该微博没有可下载的图片或视频");
            }
            cache.put(mid, new CachedPost(result, Instant.now().plusSeconds(600)));
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("微博解析失败: {}", e.getMessage());
            throw new BusinessException("微博解析失败：" + e.getMessage());
        }
    }

    /**
     * 从各种微博 URL 形态中提取 mid（base62 帖子 ID）
     */
    private String extractMid(String url) {
        Matcher detail = DETAIL_RE.matcher(url);
        if (detail.find()) {
            return detail.group(1);
        }
        Matcher uidMid = UID_MID_RE.matcher(url);
        if (uidMid.find()) {
            return uidMid.group(2);
        }
        // 兜底：取最后一个字母数字路径段
        String path = URI.create(url).getPath();
        String[] segs = path.split("/");
        for (int i = segs.length - 1; i >= 0; i--) {
            if (segs[i].matches("[A-Za-z0-9]+") && !segs[i].matches("\\d+")) {
                return segs[i];
            }
        }
        throw new BusinessException("无法识别微博帖子链接");
    }

    /**
     * 将 statuses/show 的 data 节点归一化为内部结构：title/uploader/thumbnail/uploadDate/media[]
     */
    private ObjectNode mapPost(JsonNode status) {
        ObjectNode result = mapper.createObjectNode();
        String rawText = status.path("text").asText("");
        String plain = stripHtml(rawText);
        // title 取正文首行（截断到 60 字），description 保留完整正文
        String firstLine = plain.split("\\r?\\n", 2)[0].trim();
        String title = firstLine.isEmpty() ? "微博" : firstLine;
        if (title.length() > 60) {
            title = title.substring(0, 60).trim();
        }
        result.put("title", title);
        result.put("description", plain.isBlank() ? null : plain);

        JsonNode user = status.path("user");
        String nick = user.path("screen_name").asText("");
        result.put("uploader", nick.isEmpty() ? null : nick);

        String createdAt = status.path("created_at").asText("");
        if (!createdAt.isBlank()) {
            try {
                ZonedDateTime zdt = ZonedDateTime.parse(createdAt, WB_DATE);
                result.put("uploadDate", zdt.toLocalDate().toString());
            } catch (Exception ignored) {
                result.put("uploadDate", createdAt);
            }
        }

        ArrayNode mediaArr = result.putArray("media");

        // 视频：page_info.type == "video"，取 media_info.stream_url_hd / stream_url
        JsonNode pageInfo = status.get("page_info");
        if (pageInfo != null && "video".equals(pageInfo.path("type").asText())) {
            ObjectNode v = pickVideo(pageInfo);
            if (v != null) {
                mediaArr.add(v);
            }
            double sec = pageInfo.path("media_info").path("duration").asDouble(0);
            if (sec > 0) {
                long s = Math.round(sec);
                result.put("duration", s);
                result.put("durationString", String.format("%d:%02d", s / 60, s % 60));
            }
        }

        // 图片：pics[].large.url（大图），缺失则回退 url
        for (JsonNode pic : status.path("pics")) {
            ObjectNode img = pickImage(pic);
            if (img != null) {
                mediaArr.add(img);
            }
        }
        // 单图帖 pics 可能为空，用顶层 original_pic / bmiddle_pic 兜底
        boolean hasImage = false;
        for (JsonNode m : mediaArr) {
            if ("image".equals(m.path("type").asText())) {
                hasImage = true;
                break;
            }
        }
        if (mediaArr.isEmpty() || !hasImage) {
            String single = status.path("original_pic").asText("");
            if (single.isBlank()) {
                single = status.path("bmiddle_pic").asText("");
            }
            if (!single.isBlank()) {
                ObjectNode img = mapper.createObjectNode();
                img.put("type", "image");
                img.put("url", toHttps(single));
                mediaArr.add(img);
            }
        }

        if (!mediaArr.isEmpty()) {
            JsonNode first = mediaArr.get(0);
            String thumb = "video".equals(first.path("type").asText())
                    ? first.path("cover").asText(null)
                    : first.path("url").asText(null);
            if (thumb != null) {
                result.put("thumbnail", thumb);
            }
        }
        return result;
    }

    private ObjectNode pickVideo(JsonNode pageInfo) {
        JsonNode mediaInfo = pageInfo.path("media_info");
        String streamUrl = mediaInfo.path("stream_url_hd").asText("");
        if (streamUrl.isBlank()) {
            streamUrl = mediaInfo.path("stream_url").asText("");
        }
        if (streamUrl.isBlank()) {
            // page_info.urls 也可能下发直链
            JsonNode urls = pageInfo.get("urls");
            if (urls != null && urls.isObject()) {
                for (JsonNode u : urls) {
                    if (u.isTextual() && !u.asText().isBlank()) {
                        streamUrl = u.asText();
                        break;
                    }
                }
            }
        }
        if (streamUrl.isBlank()) {
            return null;
        }
        ObjectNode v = mapper.createObjectNode();
        v.put("type", "video");
        v.put("url", toHttps(streamUrl));
        String cover = pageInfo.path("page_pic").path("url").asText("");
        if (!cover.isBlank()) {
            v.put("cover", toHttps(cover));
        }
        return v;
    }

    private ObjectNode pickImage(JsonNode pic) {
        String large = pic.path("large").path("url").asText("");
        String url = large.isBlank() ? pic.path("url").asText("") : large;
        if (url.isBlank()) {
            return null;
        }
        ObjectNode m = mapper.createObjectNode();
        m.put("type", "image");
        m.put("url", toHttps(url));
        JsonNode geo = pic.path("large").path("geo");
        if (geo.isMissingNode()) {
            geo = pic.path("geo");
        }
        int w = geo.path("width").asInt(0);
        int h = geo.path("height").asInt(0);
        if (w > 0) m.put("width", w);
        if (h > 0) m.put("height", h);
        return m;
    }

    // ===== 下载实现 =====

    private void streamSingle(JsonNode item, String baseName, HttpServletResponse response) throws Exception {
        HttpResponse<InputStream> resp = openMedia(item.path("url").asText());
        String fallbackExt = "image".equals(item.path("type").asText()) ? "jpg" : "mp4";
        byte[] first = resp.body().readNBytes(12);
        String ext = extFromMagic(first, extFromResponse(resp, fallbackExt));
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
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", disposition(baseName + ".zip"));
        response.setHeader("Cache-Control", "no-store");
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
            int idx = 1;
            for (JsonNode item : media) {
                HttpResponse<InputStream> resp = openMedia(item.path("url").asText());
                boolean isImage = "image".equals(item.path("type").asText());
                byte[] head = resp.body().readNBytes(12);
                String ext = extFromMagic(head, extFromResponse(resp, isImage ? "jpg" : "mp4"));
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

    private HttpResponse<InputStream> openMedia(String mediaUrl) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(mediaUrl))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "image/*,video/*,*/*;q=0.8")
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new BusinessException("微博资源返回状态码 " + resp.statusCode());
        }
        return resp;
    }

    private List<JsonNode> collect(JsonNode post) {
        List<JsonNode> list = new ArrayList<>();
        post.path("media").forEach(list::add);
        return list;
    }

    // ===== 工具 =====

    private static Integer intOrNull(JsonNode n) {
        int v = n.asInt(0);
        return v > 0 ? v : null;
    }

    private static String toHttps(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    /**
     * 去除 HTML 标签并解码常用实体，压缩多余空白（保留换行）
     */
    private static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = TAG_RE.matcher(html).replaceAll("");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        text = SPACE_RE.matcher(text).replaceAll(" ");
        // 压缩连续空行
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

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

    private String extFromResponse(HttpResponse<?> resp, String fallback) {
        String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (ct.contains("png")) return "png";
        if (ct.contains("webp")) return "webp";
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
        if (ct.contains("gif")) return "gif";
        if (ct.contains("mp4")) return "mp4";
        return fallback;
    }

    private String extFromMagic(byte[] head, String fallback) {
        if (head.length >= 4) {
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return "jpg";
            if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') return "webp";
            if (head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "png";
            if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return "gif";
        }
        if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return "mp4";
        }
        return fallback;
    }

    private String imageContentType(String ext) {
        return switch (ext) {
            case "webp" -> "image/webp";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
    }

    private String disposition(String filename) {
        return "attachment; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sanitizeTitle(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t#]", " ").trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        return cleaned.isEmpty() ? "weibo" : cleaned;
    }

    private record CachedPost(JsonNode data, Instant expireAt) {
    }
}
