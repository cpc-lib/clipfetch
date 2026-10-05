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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐解析：
 * 1. 从分享链接提取 songid / songmid
 * 2. c.y.qq.com fcg_play_single_song 取歌曲元信息（标题、歌手、专辑、封面、时长、各品质大小）
 * 3. u.y.qq.com musicu.fcg (vkey.GetVkeyServer) 取可播放直链
 *    - 免费歌曲直接返回直链
 *    - VIP 歌曲需上传 QQ 音乐 cookies（uin），否则回退 30 秒试听或报错
 */
@Slf4j
@Service
public class QQMusicParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern SONG_ID = Pattern.compile("[?&]songid=(\\d+)");
    private static final Pattern SONG_MID = Pattern.compile("[?&]songmid=([A-Za-z0-9]+)");
    private static final Pattern UIN = Pattern.compile("(?:^|[;&\\s])uin=o?(\\d+)");
    private static final String COVER_TPL = "https://y.gtimg.cn/music/photo_new/T002R300x300M000%s.jpg";

    private final int parseTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 解析缓存：songmid → 音频直链（vkey 有时效，缓存 1 小时，下载时若失效重新取） */
    private final Map<String, String> urlCache = new ConcurrentHashMap<>();

    public QQMusicParser(@Value("${app.parse-timeout:60}") int parseTimeout) {
        this.parseTimeout = parseTimeout;
    }

    public boolean supports(String url) {
        return Platform.from(url) == Platform.QQMUSIC;
    }

    public VideoInfo parse(String url, String cookies) {
        String songId = extractSongId(url);
        JsonNode song = fetchSongInfo(songId);
        String songMid = song.path("mid").asText("");
        String mediaMid = song.path("file").path("media_mid").asText("");
        String title = song.path("title").asText("未知歌曲");
        String singer = extractSinger(song);
        String album = song.path("album").path("name").asText(null);
        String albumMid = song.path("album").path("mid").asText(null);
        String cover = albumMid != null ? String.format(COVER_TPL, albumMid) : null;
        long interval = song.path("interval").asLong(0);
        JsonNode file = song.path("file");
        long size128 = file.path("size_128mp3").asLong(0);
        long size320 = file.path("size_320mp3").asLong(0);
        long sizeFlac = file.path("size_flac").asLong(0);

        // 取音频直链（优先高品质，依次回退）
        String audioUrl = null;
        String ext = "m4a";
        String label = "标准品质 128kbps";
        if (sizeFlac > 0) {
            String flac = fetchPlayUrl(songMid, mediaMid, "F000", ".flac", cookies);
            if (flac != null) { audioUrl = flac; ext = "flac"; label = "无损 FLAC"; }
        }
        if (audioUrl == null && size320 > 0) {
            String mp3 = fetchPlayUrl(songMid, mediaMid, "M500", ".mp3", cookies);
            if (mp3 != null) { audioUrl = mp3; ext = "mp3"; label = "高品质 320kbps"; }
        }
        if (audioUrl == null) {
            String m4a = fetchPlayUrl(songMid, mediaMid, "C400", ".m4a", cookies);
            if (m4a != null) { audioUrl = m4a; ext = "m4a"; label = "标准品质 128kbps"; }
        }
        if (audioUrl == null) {
            throw new BusinessException("该歌曲为 VIP 专属，暂不支持免费下载。"
                    + "可在「我的 Cookies」上传 QQ 音乐 cookies 后重试。");
        }
        urlCache.put(songMid, audioUrl);

        long filesize = "flac".equals(ext) ? sizeFlac : ("mp3".equals(ext) ? size320 : size128);
        List<FormatInfo> formats = List.of(new FormatInfo(
                "qqmusic_audio", ext, null, null,
                filesize > 0 ? filesize : null, null,
                "none", "audio", label, false, true, true));

        return new VideoInfo(
                songMid,
                title,
                cover,
                interval > 0 ? interval : null,
                interval > 0 ? YtDlpService.formatDuration(interval) : null,
                singer,
                Platform.QQMUSIC.display,
                null,
                song.path("time_public").asText(null),
                formats,
                null,
                List.of(),
                false,
                album != null ? "专辑：" + album : null
        );
    }

    /**
     * 下载时复用解析缓存的直链；缓存丢失时重新解析。由调用方用 DownloadService 流式转发。
     */
    public String resolveAudioUrl(String url, String cookies) {
        String songMid = extractSongMid(url);
        String cached = urlCache.get(songMid);
        if (cached != null) {
            return cached;
        }
        parse(url, cookies);
        return urlCache.get(songMid);
    }

    // ===== 内部实现 =====

    private String extractSongId(String url) {
        Matcher m = SONG_ID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = SONG_MID.matcher(url);
        if (m.find()) {
            return m.group(1); // songmid 也能作为 fcg_play_single_song 的 songid 参数（接受 mid）
        }
        throw new BusinessException("无法从链接中识别歌曲 ID");
    }

    private String extractSongMid(String url) {
        Matcher m = SONG_MID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        // 只有 songid 时，从元信息接口取 songmid
        String songId = extractSongId(url);
        JsonNode song = fetchSongInfo(songId);
        return song.path("mid").asText("");
    }

    private String extractSinger(JsonNode song) {
        JsonNode singers = song.path("singer");
        if (singers.isArray() && singers.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode s : singers) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(s.path("name").asText(""));
            }
            return sb.toString();
        }
        return "未知歌手";
    }

    private JsonNode fetchSongInfo(String songId) {
        String api = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
                + "?songid=" + URLEncoder.encode(songId, StandardCharsets.UTF_8)
                + "&tpl=yqq_song_detail&format=json&loginUin=0&hostUin=0&notice=0"
                + "&platform=yqq&needNewCode=0&inCharset=utf8&outCharset=utf-8";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                throw new BusinessException("未找到歌曲信息");
            }
            return data.get(0);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取 QQ 音乐歌曲信息失败：" + e.getMessage());
        }
    }

    /**
     * 取指定品质的可播放直链。
     *
     * @param prefix  文件名前缀：C400(128k m4a) / M500(320k mp3) / F000(flac)
     * @param ext     文件扩展名
     * @param cookies 用户 cookies（VIP 歌曲需要 uin）
     * @return 可播放直链，无权限时返回 null
     */
    private String fetchPlayUrl(String songMid, String mediaMid, String prefix, String ext, String cookies) {
        String guid = String.valueOf(ThreadLocalRandom.current().nextInt(100000000, 999999999));
        String uin = extractUin(cookies);
        String filename = prefix + (mediaMid != null && !mediaMid.isBlank() ? mediaMid : songMid) + ext;
        String body = "{\"req_0\":{\"method\":\"CgiGetVkey\",\"module\":\"vkey.GetVkeyServer\","
                + "\"param\":{\"guid\":\"" + guid + "\",\"songmid\":[\"" + songMid + "\"],"
                + "\"filename\":[\"" + filename + "\"],\"songtype\":[0],\"uin\":\"" + uin + "\","
                + "\"loginflag\":" + (uin.isEmpty() ? "0" : "1") + ",\"platform\":\"20\"}}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg"))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode midInfo = mapper.readTree(resp.body())
                    .path("req_0").path("data").path("midurlinfo").path(0);
            String purl = midInfo.path("purl").asText("");
            if (purl.isBlank()) {
                return null;
            }
            JsonNode sips = mapper.readTree(resp.body()).path("req_0").path("data").path("sip");
            String sip = sips.isArray() && sips.size() > 0 ? sips.get(0).asText("") : "http://aqqmusic.tc.qq.com/";
            return sip + purl;
        } catch (Exception e) {
            log.warn("QQ 音乐取播放地址失败 ({}): {}", filename, e.getMessage());
            return null;
        }
    }

    private String extractUin(String cookies) {
        if (cookies == null || cookies.isBlank()) {
            return "";
        }
        Matcher m = UIN.matcher(cookies);
        return m.find() ? m.group(1) : "";
    }
}
