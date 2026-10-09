package com.fvd.video.application.parser.video;

import com.fvd.video.application.ParseContext;
import com.fvd.video.application.parser.AbstractVideoParser;
import com.fvd.video.application.parser.CookiePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.MediaItem;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 小红书笔记解析（图文 / 视频，纯 Java 实现，不抓取评论）。
 * 数据来源：以移动端 UA 请求笔记页，SSR 直接在 window.__INITIAL_STATE__ 中下发完整笔记数据
 * （noteData.data.noteData：title/desc/user/imageList/video），匿名可访问，无需 cookies。
 * 支持 www.xiaohongshu.com/discovery/item|/explore/{id} 与 xhslink.com 短链（跟随重定向）。
 * 下载：服务端直连 CDN（Referer: xiaohongshu.com），单项流式回写，多项打包 ZIP。
 * CDN 直链带时效签名，解析结果缓存 10 分钟供下载使用。
 */
@Slf4j
@Service
public class RednoteParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String REFERER = "https://www.xiaohongshu.com/";
    public static final String FORMAT_ID = "images";
    private static final Pattern STATE_RE = Pattern.compile("__INITIAL_STATE__\\s*=\\s*");
    /** XHS 序列化会产生裸 undefined（非法 JSON），转换为 null */
    private static final Pattern UNDEFINED_RE = Pattern.compile("(?<=[:,\\[])undefined(?=[,\\]}])");

    private final int parseTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedPost> cache = new ConcurrentHashMap<>();

    public RednoteParser(@Value("${app.parse-timeout:60}") int parseTimeout) {
        this.parseTimeout = parseTimeout;
    }

    @Override
    public Platform platform() {
        return Platform.REDNOTE;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        JsonNode post = fetchPost(url);
        List<JsonNode> media = collect(post);
        long images = media.stream().filter(m -> "image".equals(m.path("type").asText())).count();
        long videos = media.stream().filter(m -> "video".equals(m.path("type").asText())).count();
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
                post.path("title").asText("小红书笔记"),
                post.path("thumbnail").asText(null),
                duration,
                post.path("durationString").asText(null),
                post.path("uploader").asText(null),
                Platform.REDNOTE.display,
                null,
                null,
                List.of(format), mediaList, List.of(), false,
                post.path("description").asText(null));
    }

    public void download(String url, String title, HttpServletResponse response) {
        JsonNode post = fetchPost(url);
        List<JsonNode> media = collect(post);
        String baseName = sanitizeTitle(title != null ? title : post.path("title").asText("rednote"));
        try {
            if (media.size() == 1) {
                streamSingle(media.get(0), baseName, response);
            } else {
                streamZip(media, baseName, response);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("小红书内容下载失败：" + e.getMessage());
        }
    }

    // ===== 解析（移动端 UA + SSR __INITIAL_STATE__） =====

    private JsonNode fetchPost(String url) {
        CachedPost cached = cache.get(url);
        if (cached != null && cached.expireAt.isAfter(Instant.now())) {
            return cached.data;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    // 小红书为国内站点，显式直连，避免走外网代理触发风控
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("小红书页面返回状态码 " + resp.statusCode());
            }
            JsonNode state = extractState(resp.body());
            ObjectNode result = mapPost(state);
            if (collect(result).isEmpty()) {
                throw new BusinessException("未解析到笔记内容（可能已删除、为私密笔记或触发了风控，请稍后重试）");
            }
            cache.put(url, new CachedPost(result, Instant.now().plusSeconds(600)));
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("小红书页面解析失败: {}", e.getMessage());
            throw new BusinessException("小红书笔记解析失败：" + e.getMessage());
        }
    }

    /**
     * 从 HTML 中提取 window.__INITIAL_STATE__ 的 JSON 对象（按字符串感知的括号配对截取）
     */
    private JsonNode extractState(String html) {
        Matcher m = STATE_RE.matcher(html);
        if (!m.find()) {
            throw new BusinessException("页面中未找到笔记数据");
        }
        int start = html.indexOf('{', m.end());
        boolean inStr = false;
        boolean esc = false;
        int depth = 0;
        for (int i = start; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    inStr = false;
                }
                continue;
            }
            if (ch == '"') {
                inStr = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    String json = UNDEFINED_RE.matcher(html.substring(start, i + 1)).replaceAll("null");
                    try {
                        return mapper.readTree(json);
                    } catch (Exception e) {
                        throw new BusinessException("笔记数据解析失败：" + e.getMessage());
                    }
                }
            }
        }
        throw new BusinessException("笔记数据不完整");
    }

    /**
     * 将 SSR state 归一化为内部结构：title/uploader/thumbnail/duration/media[]。
     * 兼容移动端（noteData.data.noteData）与桌面端（note.noteDetailMap[id].note）两种形态。
     */
    private ObjectNode mapPost(JsonNode state) {
        JsonNode note = state.path("noteData").path("data").path("noteData");
        if (note.isMissingNode() || note.path("hasError").asBoolean(false)) {
            JsonNode detailMap = state.path("note").path("noteDetailMap");
            if (detailMap.isObject() && detailMap.size() > 0) {
                java.util.Iterator<JsonNode> it = detailMap.elements();
                while (it.hasNext()) {
                    JsonNode detail = it.next().path("note");
                    if (!detail.isMissingNode()) {
                        note = detail;
                        break;
                    }
                }
            }
        }
        if (note.isMissingNode() || !note.has("noteId")) {
            if (state.path("noteData").path("hasError").asBoolean(false)) {
                throw new BusinessException("小红书返回异常页面（可能触发了风控验证），请稍后重试");
            }
            throw new BusinessException("未找到笔记数据（可能需要登录后才能查看）");
        }

        ObjectNode result = mapper.createObjectNode();
        String title = note.path("title").asText("").trim();
        // 去掉 XHS Web 话题标记（"#美甲长甲[话题]#" → "#美甲长甲"）
        String desc = note.path("desc").asText("").replace("[话题]#", "").trim();
        if (title.isEmpty()) {
            title = desc.isEmpty() ? "小红书笔记" : desc.split("\\r?\\n")[0];
        }
        result.put("title", title);
        result.put("description", desc.isEmpty() ? null : desc);
        String nick = note.path("user").path("nickName").asText("");
        if (nick.isEmpty()) {
            nick = note.path("user").path("nickname").asText("");
        }
        result.put("uploader", nick.isEmpty() ? null : nick);

        ArrayNode mediaArr = result.putArray("media");
        JsonNode video = note.get("video");
        if (video != null && video.isObject() && video.has("media")) {
            ObjectNode v = pickVideo(video, note);
            if (v != null) {
                mediaArr.add(v);
            }
            double seconds = video.path("cap").path("duration").asDouble(0);
            if (seconds > 0) {
                long s = Math.round(seconds);
                result.put("duration", s);
                result.put("durationString", String.format("%d:%02d", s / 60, s % 60));
            }
        }
        for (JsonNode img : note.path("imageList")) {
            ObjectNode item = pickImage(img);
            if (item != null) {
                // 视频笔记的 imageList 是封面帧，已作为视频 cover，不作为独立媒体项
                if (mediaArr.isEmpty() || !"video".equals(mediaArr.get(0).path("type").asText())) {
                    mediaArr.add(item);
                }
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

    /**
     * 视频：优先 h264 中码率最高的 masterUrl，其次 h265/av1；附带备用地址与封面
     */
    private ObjectNode pickVideo(JsonNode video, JsonNode note) {
        JsonNode streams = video.path("media").path("stream");
        String[] preference = {"h264", "h265", "av1"};
        JsonNode best = null;
        for (String codec : preference) {
            JsonNode arr = streams.path(codec);
            if (!arr.isArray() || arr.isEmpty()) {
                continue;
            }
            int bestRate = -1;
            for (JsonNode v : arr) {
                int rate = v.path("videoBitrate").asInt(v.path("avgVideoBitrate").asInt(0));
                if (rate > bestRate) {
                    bestRate = rate;
                    best = v;
                }
            }
            if (best != null) {
                break;
            }
        }
        if (best == null || !best.hasNonNull("masterUrl")) {
            return null;
        }
        ObjectNode m = mapper.createObjectNode();
        m.put("type", "video");
        m.put("url", toHttps(best.get("masterUrl").asText()));
        m.put("width", best.path("width").asInt(0));
        m.put("height", best.path("height").asInt(0));
        ArrayNode backups = m.putArray("backupUrls");
        for (JsonNode b : best.path("backupUrls")) {
            if (b.isTextual() && !b.asText().isBlank()) {
                backups.add(toHttps(b.asText()));
            }
        }
        String cover = coverOf(note);
        if (cover != null) {
            m.put("cover", cover);
        }
        return m;
    }

    /**
     * 图片：优先 ci.xiaohongshu.com/{fileId} 原图（无水印、无时效签名、匿名可访问）；
     * fileId 缺失时回退 infoList 模板图（h5_1080jpg，分享页带水印版本）
     */
    private ObjectNode pickImage(JsonNode img) {
        String url = null;
        String fileId = img.path("fileId").asText("");
        if (!fileId.isBlank()) {
            url = ciImageUrl(fileId);
        }
        if (url == null) {
            for (JsonNode q : img.path("infoList")) {
                if ("H5_DTL".equals(q.path("imageScene").asText()) && q.hasNonNull("url")) {
                    url = q.get("url").asText();
                    break;
                }
            }
        }
        if (url == null && img.hasNonNull("url")) {
            url = img.get("url").asText();
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        // 实况图按视频处理：静帧在 ci 上是 HEIC 浏览器无法显示，主内容改用配套 MP4 流
        if (img.path("livePhoto").asBoolean(false)) {
            ObjectNode live = pickLiveVideo(img, url);
            if (live != null) {
                return live;
            }
        }
        ObjectNode m = mapper.createObjectNode();
        m.put("type", "image");
        m.put("url", toHttps(url));
        m.put("width", img.path("width").asInt(0));
        m.put("height", img.path("height").asInt(0));
        return m;
    }

    /**
     * 实况图 → 视频项：url 为 h264 最高码率流（h265/av1 兜底），cover 用静帧地址（H5_DTL JPEG 可显示）
     */
    private ObjectNode pickLiveVideo(JsonNode img, String coverUrl) {
        JsonNode streams = img.path("stream");
        String[] preference = {"h264", "h265", "av1"};
        JsonNode best = null;
        for (String codec : preference) {
            JsonNode arr = streams.path(codec);
            if (!arr.isArray() || arr.isEmpty()) {
                continue;
            }
            int bestRate = -1;
            for (JsonNode v : arr) {
                int rate = v.path("videoBitrate").asInt(v.path("avgBitrate").asInt(0));
                if (rate > bestRate) {
                    bestRate = rate;
                    best = v;
                }
            }
            if (best != null) {
                break;
            }
        }
        if (best == null || !best.hasNonNull("masterUrl")) {
            return null;
        }
        ObjectNode m = mapper.createObjectNode();
        m.put("type", "video");
        m.put("url", toHttps(best.get("masterUrl").asText()));
        m.put("width", best.path("width").asInt(0));
        m.put("height", best.path("height").asInt(0));
        ArrayNode backups = m.putArray("backupUrls");
        for (JsonNode b : best.path("backupUrls")) {
            if (b.isTextual() && !b.asText().isBlank()) {
                backups.add(toHttps(b.asText()));
            }
        }
        // 封面优先 H5_DTL（JPEG 可显示），其次静帧原图
        String cover = null;
        for (JsonNode q : img.path("infoList")) {
            if ("H5_DTL".equals(q.path("imageScene").asText()) && q.hasNonNull("url")) {
                cover = toHttps(q.get("url").asText());
                break;
            }
        }
        m.put("cover", cover != null ? cover : toHttps(coverUrl));
        return m;
    }

    private String coverOf(JsonNode note) {
        for (JsonNode img : note.path("imageList")) {
            String fileId = img.path("fileId").asText("");
            if (!fileId.isBlank()) {
                return ciImageUrl(fileId);
            }
            for (JsonNode q : img.path("infoList")) {
                String scene = q.path("imageScene").asText("");
                if (q.hasNonNull("url") && (scene.equals("H5_DTL") || scene.equals("H5_PRV"))) {
                    return toHttps(q.get("url").asText());
                }
            }
            if (img.hasNonNull("url")) {
                return toHttps(img.get("url").asText());
            }
        }
        return null;
    }

    // ===== 下载实现 =====

    private void streamSingle(JsonNode item, String baseName, HttpServletResponse response) throws Exception {
        HttpResponse<InputStream> resp = fetchMedia(item, null);
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
                boolean isImage = "image".equals(item.path("type").asText());
                writeZipEntry(zip, idx + (isImage ? ".jpg" : ".mp4"),
                        item.path("url").asText(), item.path("backupUrls"));
                idx++;
            }
            zip.finish();
            zip.flush();
        }
    }

    /**
     * 向 ZIP 写入一个条目：主地址失败时依次尝试备用地址，按响应魔数校正扩展名（以传入文件名为准）
     */
    private void writeZipEntry(ZipOutputStream zip, String entryName, String primary, JsonNode backups) throws Exception {
        List<String> urls = new ArrayList<>();
        urls.add(primary);
        if (backups != null) {
            backups.forEach(b -> {
                if (b.isTextual() && !b.asText().isBlank()) {
                    urls.add(b.asText());
                }
            });
        }
        Exception last = null;
        for (String u : urls) {
            try {
                HttpResponse<InputStream> resp = openMedia(u);
                // 按魔数校正扩展名（实况图静帧在 ci 上是 HEIC，浏览器无法显示，ZIP 内需真实扩展名）
                byte[] head = resp.body().readNBytes(12);
                String actualExt = extFromMagic(head, null);
                if (actualExt != null && !entryName.endsWith("." + actualExt)) {
                    entryName = entryName.replaceAll("\\.[^.]+$", "." + actualExt);
                }
                zip.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = resp.body()) {
                    zip.write(head);
                    in.transferTo(zip);
                }
                zip.closeEntry();
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new BusinessException("小红书资源下载失败：" + entryName + "（"
                + (last == null ? "所有地址均不可用" : last.getMessage()) + "）");
    }

    /**
     * 拉取媒体流；主地址失败时依次尝试 backupUrls（单项直下场景）
     */
    private HttpResponse<InputStream> fetchMedia(JsonNode item, String triedUrl) throws Exception {
        String mediaUrl = triedUrl != null ? triedUrl : item.path("url").asText();
        try {
            return openMedia(mediaUrl);
        } catch (BusinessException e) {
            if (triedUrl == null) {
                for (JsonNode b : item.path("backupUrls")) {
                    try {
                        return openMedia(b.asText());
                    } catch (Exception ignored) {
                        // 继续尝试下一个备用地址
                    }
                }
            }
            throw e;
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
            throw new BusinessException("小红书资源返回状态码 " + resp.statusCode());
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

    /** ci 原图地址：无水印、无时效签名、匿名可访问 */
    private static String ciImageUrl(String fileId) {
        return "https://ci.xiaohongshu.com/" + fileId;
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
        if (ct.contains("png")) {
            return "png";
        }
        if (ct.contains("webp")) {
            return "webp";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return "jpg";
        }
        if (ct.contains("mp4")) {
            return "mp4";
        }
        return fallback;
    }

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
        // ftyp box：HEIC/MP4 共用容器，需读 brand 字段区分
        if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, StandardCharsets.ISO_8859_1);
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1")) {
                return "heic";
            }
            if (brand.equals("isom") || brand.equals("mp42") || brand.equals("mp41") || brand.equals("avc1")) {
                return "mp4";
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
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t#]", " ").trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        return cleaned.isEmpty() ? "rednote" : cleaned;
    }

    private record CachedPost(JsonNode data, Instant expireAt) {
    }
}
