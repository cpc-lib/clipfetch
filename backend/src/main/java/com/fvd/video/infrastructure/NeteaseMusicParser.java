package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云音乐解析：
 * 1. 从分享链接提取 song id（支持 /song?id=xxx 和第三方解析服务）
 * 2. 网易云官方 API 取歌曲元信息（标题、歌手、专辑封面、时长）
 * 3. 官方 weapi 加密接口取可播放直链（需上传 cookies，含 MUSIC_U）
 * 4. cookies 必需：用于官方接口身份验证（支持 VIP 歌曲）
 */
@Slf4j
@Service
public class NeteaseMusicParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern SONG_ID = Pattern.compile("[?&]id=(\\d+)");
    private static final String COVER_TPL = "https://p1.music.126.net/%s.jpg";

    private final int parseTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 解析缓存：songid:formatId → 音频直链 */
    private final Map<String, String> urlCache = new ConcurrentHashMap<>();
    /** 扩展名缓存：songid:formatId → 文件扩展名（flac/mp3） */
    private final Map<String, String> extCache = new ConcurrentHashMap<>();
    /** 标题缓存：songid → 歌曲标题 */
    private final Map<String, String> titleCache = new ConcurrentHashMap<>();
    /** 歌手缓存：songid → 歌手名 */
    private final Map<String, String> artistCache = new ConcurrentHashMap<>();

    public NeteaseMusicParser(@Value("${app.parse-timeout:60}") int parseTimeout) {
        this.parseTimeout = parseTimeout;
    }

    public boolean supports(String url) {
        return Platform.from(url) == Platform.NETEASE_MUSIC;
    }

    public VideoInfo parse(String url, String cookies) {
        String songId = extractSongId(url);
        JsonNode song = fetchSongInfo(songId);
        String title = song.path("name").asText("未知歌曲");
        String artist = extractArtist(song);
        // 缓存标题和歌手用于下载时生成文件名
        titleCache.put(songId, title);
        artistCache.put(songId, artist);
        long duration = song.path("duration").asLong(0) / 1000; // 毫秒转秒
        String albumName = song.path("album").path("name").asText(null);
        String cover = song.path("album").path("picUrl").asText(null);

        // 列出全部品质（FLAC 优先，其次 128K / 192K / 320K），仅展示元数据、不请求播放直链，规避风控
        List<FormatInfo> formats = new ArrayList<>();

        JsonNode sq = song.path("sqMusic");
        if (sq != null && sq.has("size")) {
            formats.add(new FormatInfo(
                    "netease_flac", sq.path("extension").asText("flac"), null, null,
                    sq.path("size").asLong(), null,
                    "none", "audio", "无损品质 FLAC", false, true, true));
        }
        JsonNode l = song.path("lMusic");
        if (l != null && l.has("size")) {
            formats.add(new FormatInfo(
                    "netease_128k", "mp3", null, null,
                    l.path("size").asLong(), null,
                    "none", "audio", "标准品质 128kbps", false, true, true));
        }
        JsonNode m = song.path("mMusic");
        if (m != null && m.has("size")) {
            formats.add(new FormatInfo(
                    "netease_192k", "mp3", null, null,
                    m.path("size").asLong(), null,
                    "none", "audio", "标准品质 192kbps", false, true, true));
        }
        JsonNode h = song.path("hMusic");
        if (h != null && h.has("size")) {
            formats.add(new FormatInfo(
                    "netease_320k", "mp3", null, null,
                    h.path("size").asLong(), null,
                    "none", "audio", "高品质 320kbps", false, true, true));
        }

        if (formats.isEmpty()) {
            formats.add(new FormatInfo(
                    "netease_flac", "flac", null, null,
                    null, null,
                    "none", "audio", "无损品质 FLAC", false, true, true));
        }

        return new VideoInfo(
                songId,
                title,
                cover,
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                artist,
                Platform.NETEASE_MUSIC.display,
                null,
                null, // 发布日期需要从其他接口获取
                formats,
                null,
                List.of(),
                false,
                albumName != null ? "专辑：" + albumName : null
        );
    }

    /**
     * 下载时按所选品质请求官方 weapi 播放直链；缓存 songId:formatId → 直链/扩展名。
     * 若标题/歌手缓存缺失（如重启后），先调 parse 填充元数据缓存。
     */
    public String resolveAudioUrl(String url, String cookies, String formatId) {
        String songId = extractSongId(url);
        String fid = formatId == null || formatId.isBlank() ? "netease_flac" : formatId;
        String cacheKey = songId + ":" + fid;
        String cached = urlCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 标题/歌手缓存缺失时，先调 parse 填充（一次元信息请求）
        if (!titleCache.containsKey(songId)) {
            parse(url, cookies);
        }

        String cookieHeader = convertNetscapeToHeader(cookies);
        String level = switch (fid) {
            case "netease_128k" -> "standard";
            case "netease_192k" -> "higher";
            case "netease_320k" -> "exhigh";
            default -> "lossless";
        };
        String ext = "netease_flac".equals(fid) ? "flac" : "mp3";

        String audioUrl = fetchPlayUrl(songId, cookieHeader, level);
        if (audioUrl == null) {
            throw new BusinessException("获取网易云音乐播放地址失败，请稍后重试");
        }
        urlCache.put(cacheKey, audioUrl);
        extCache.put(cacheKey, ext);
        return audioUrl;
    }

    /**
     * 生成下载文件名：歌手 - 标题.扩展名（从解析缓存中取）
     */
    public String resolveFilename(String url, String formatId) {
        String songId = extractSongId(url);
        String fid = formatId == null || formatId.isBlank() ? "netease_flac" : formatId;
        String ext = extCache.getOrDefault(songId + ":" + fid,
                "netease_flac".equals(fid) ? "flac" : "mp3");
        String title = titleCache.getOrDefault(songId, "netease-music");
        String artist = artistCache.getOrDefault(songId, "");
        String base = artist.isBlank() ? title : artist + " - " + title;
        // 清理非法文件名字符
        String safe = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe + "." + ext;
    }

    // ===== 内部实现 =====

    private String extractSongId(String url) {
        Matcher m = SONG_ID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        throw new BusinessException("无法从链接中识别网易云音乐歌曲 ID");
    }

    private String extractArtist(JsonNode song) {
        JsonNode artists = song.path("artists");
        if (artists.isArray() && artists.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode a : artists) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(a.path("name").asText(""));
            }
            return sb.toString();
        }
        return "未知歌手";
    }

    /**
     * 从网易云官方 API 获取歌曲元信息
     */
    private JsonNode fetchSongInfo(String songId) {
        String api = "https://music.163.com/api/song/detail/?id=" + songId + "&ids=[" + songId + "]";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(resp.body());
            JsonNode songs = root.path("songs");
            if (!songs.isArray() || songs.size() == 0) {
                throw new BusinessException("未找到网易云音乐歌曲信息");
            }
            return songs.get(0);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取网易云音乐歌曲信息失败：" + e.getMessage());
        }
    }

    /**
     * 将 Netscape 格式 cookies 文件内容转换为 HTTP Cookie header 格式
     */
    private String convertNetscapeToHeader(String netscapeContent) {
        if (netscapeContent == null || netscapeContent.isBlank()) {
            return null;
        }
        // 如果是 HTTP header 格式（包含 = 号），直接返回
        if (netscapeContent.contains("=") && !netscapeContent.contains("\t")) {
            return netscapeContent;
        }
        // 解析 Netscape 格式：domain\tincludeSubdomains\tpath\tsecure\texpires\tname\tvalue
        StringBuilder sb = new StringBuilder();
        for (String line : netscapeContent.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length >= 7) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(parts[5]).append("=").append(parts[6]);
            }
        }
        return sb.toString();
    }

    /**
     * 从官方 weapi 加密接口获取播放直链（需 cookies）
     */
    private String fetchPlayUrl(String songId, String cookies, String level) {
        if (cookies == null || cookies.isBlank()) {
            log.warn("网易云音乐需要上传 cookies 才能获取播放地址");
            return null;
        }

        // 提取 __csrf 用于 csrf_token 参数
        String csrf = extractCsrf(cookies);
        if (csrf == null) {
            log.warn("网易云音乐 cookies 中缺少 __csrf");
            return null;
        }

        try {
            // 构建请求体 JSON
            String requestBody = String.format(
                    "{\"ids\":\"[%s]\",\"level\":\"%s\",\"encodeType\":\"flac\",\"csrf_token\":\"%s\"}",
                    songId, level, csrf);

            // weapi 加密
            String[] encrypted = NeteaseCrypto.encrypt(requestBody);
            String params = encrypted[0];
            String encSecKey = encrypted[1];

            // 发送请求
            String api = "https://music.163.com/weapi/song/enhance/player/url/v1?csrf_token=" + csrf;
            String body = "params=" + URLEncoder.encode(params, StandardCharsets.UTF_8)
                    + "&encSecKey=" + encSecKey;

            log.info("网易云音乐 weapi 请求: api={}, csrf={}, cookies长度={}", api, csrf, cookies != null ? cookies.length() : 0);

            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookies)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.info("网易云音乐 weapi 响应: status={}, body长度={}", resp.statusCode(), resp.body().length());
            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            log.info("网易云音乐 weapi 数据: isArray={}, size={}", data.isArray(), data.isArray() ? data.size() : -1);
            if (!data.isArray() || data.size() == 0) {
                log.warn("网易云音乐 weapi 返回空数据");
                return null;
            }

            JsonNode songData = data.get(0);
            String url = songData.path("url").asText("");
            log.info("网易云音乐 weapi 返回: code={}, url长度={}, type={}, size={}",
                    songData.path("code").asText(), url.length(),
                    songData.path("type").asText(), songData.path("size").asLong());
            if (url.isBlank()) {
                log.warn("网易云音乐 weapi 返回 code={}", songData.path("code").asText());
                return null;
            }
            return url;
        } catch (Exception e) {
            log.warn("网易云音乐取播放地址失败 ({}): {}", songId, e.getMessage());
            return null;
        }
    }

    /**
     * 从 cookies 字符串中提取 __csrf
     */
    private String extractCsrf(String cookies) {
        if (cookies == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("__csrf=([^;]+)");
        Matcher matcher = pattern.matcher(cookies);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
