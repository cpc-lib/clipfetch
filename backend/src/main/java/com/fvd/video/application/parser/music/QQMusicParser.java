package com.fvd.video.application.parser.music;

import com.fvd.video.application.ParseContext;
import com.fvd.video.application.parser.AbstractVideoParser;
import com.fvd.video.application.parser.CookiePolicy;
import com.fvd.video.application.parser.TempLinkService;
import com.fvd.video.infrastructure.service.YtDlpService;
import com.fvd.video.infrastructure.sidecar.QQMusicBrowserSidecar;

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
 * QQ 音乐解析：
 * 1. 从分享链接提取 songid / songmid
 * 2. c.y.qq.com fcg_play_single_song 取歌曲元信息（标题、歌手、专辑、封面、时长、各品质大小）
 * 3. 经浏览器 sidecar（TmeWebSec 签名通道 music.vkey.GetEVkey）取可播放直链
 *    - 非会员歌曲返回 C400/M500 直链，F000 无损及 VIP 歌曲由服务端按账号权益判定
 *    - 登录态保存在 sidecar 的 Chrome profile 中，过期需扫码登录
 */
@Slf4j
@Service
public class QQMusicParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern SONG_ID = Pattern.compile("[?&]songid=(\\d+)");
    private static final Pattern SONG_MID = Pattern.compile("[?&]songmid=([A-Za-z0-9]+)");
    private static final Pattern SONG_DETAIL_MID = Pattern.compile("/songDetail/([A-Za-z0-9]+)");
    private static final Pattern PLAYLIST_ID = Pattern.compile("/playlist/(\\d+)");
    private static final String COVER_TPL = "https://y.gtimg.cn/music/photo_new/T002R300x300M000%s.jpg";

    private final int parseTimeout;
    private final QQMusicBrowserSidecar sidecar;
    private final TempLinkService tempLinkService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 解析缓存：songmid → 音频直链（vkey 有时效，缓存 1 小时，下载时若失效重新取） */
    private final Map<String, String> urlCache = new ConcurrentHashMap<>();

    public QQMusicParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                         QQMusicBrowserSidecar sidecar,
                         TempLinkService tempLinkService) {
        this.parseTimeout = parseTimeout;
        this.sidecar = sidecar;
        this.tempLinkService = tempLinkService;
    }

    @Override
    public Platform platform() {
        return Platform.QQMUSIC;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    /** 是否为 QQ 音乐歌单页（如 https://y.qq.com/n/ryqq_v2/playlist/3189592873） */
    public boolean isPlaylistUrl(String url) {
        return supports(url) && PLAYLIST_ID.matcher(url).find();
    }

    /**
     * 解析歌单页，返回歌单名及歌曲子链接列表。
     * 走 musicu.fcg uniform_get_Dissinfo 分页取全部歌曲（公开歌单匿名可访问）。
     */
    public PlaylistParseResult parsePlaylist(String url) {
        Matcher m = PLAYLIST_ID.matcher(url);
        if (!m.find()) {
            throw new BusinessException("无法从链接中识别歌单 ID");
        }
        long dissid = Long.parseLong(m.group(1));
        List<PlaylistSong> songs = new ArrayList<>();
        String playlistName = null;
        int begin = 0;
        // 接口每页最多返回 30 首，超出部分要用 song_begin 翻页
        int pageSize = 30;
        while (true) {
            JsonNode data = fetchPlaylistPage(dissid, begin, pageSize);
            if (playlistName == null) {
                playlistName = data.path("dirinfo").path("title").asText("QQ音乐歌单");
            }
            JsonNode list = data.path("songlist");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode s : list) {
                long id = s.path("id").asLong(0);
                if (id == 0) {
                    continue;
                }
                String name = s.path("title").asText(s.path("name").asText("未知歌曲"));
                // 付费类型：pay.pay_play=1 为 VIP 会员歌曲（绿钻可播）→1，其余 →0
                int vip = s.path("pay").path("pay_play").asInt(0) == 1 ? 1 : 0;
                // 子链接用 playsong.html?songid= 形式，单曲解析入口能直接识别
                String songUrl = "https://i.y.qq.com/v8/playsong.html?songid=" + id + "&songtype=0";
                songs.add(new PlaylistSong(String.valueOf(id), name, songUrl, vip));
            }
            if (list.size() < pageSize) {
                break;
            }
            begin += pageSize;
        }
        if (songs.isEmpty()) {
            throw new BusinessException("歌单为空、不存在或未公开，无法获取歌曲列表。");
        }
        log.info("QQ 音乐歌单 {} ({}) 解析到 {} 首歌曲", dissid, playlistName, songs.size());
        return new PlaylistParseResult(playlistName, songs);
    }

    /** 歌单解析结果：歌单名 + 歌曲列表 */
    public record PlaylistParseResult(String playlistName, List<PlaylistSong> songs) {}

    /** 歌单里的歌曲子链接；vip：0=免费 1=VIP 会员歌曲 */
    public record PlaylistSong(String songId, String title, String url, int vip) {}

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        if (isPlaylistUrl(url)) {
            PlaylistParseResult result = parsePlaylist(url);
            int[] counts = tempLinkService.saveTempLinks(
                    result.songs().stream()
                            .map(s -> new TempLinkService.TempSongInput(s.url(), s.title(), s.vip()))
                            .toList(), url);
            return new VideoInfo(null, result.playlistName(), null, null, null,
                    null, Platform.QQMUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条");
        }
        return parse(url);
    }

    public VideoInfo parse(String url) {
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

        // 浏览器 sidecar 一次取回全部品质直链，按 无损 → 320k → 128k 选择
        QQMusicBrowserSidecar.ResolveResult resolved = sidecar.resolve(songMid, mediaMid);
        String audioUrl = null;
        String ext = "m4a";
        String label = "标准品质 128kbps";
        if (sizeFlac > 0 && resolved.url("f000") != null) {
            audioUrl = resolved.url("f000"); ext = "flac"; label = "无损 FLAC";
        }
        if (audioUrl == null && size320 > 0 && resolved.url("m500") != null) {
            audioUrl = resolved.url("m500"); ext = "mp3"; label = "高品质 320kbps";
        }
        if (audioUrl == null && resolved.url("c400") != null) {
            audioUrl = resolved.url("c400"); ext = "m4a"; label = "标准品质 128kbps";
        }
        if (audioUrl == null) {
            throw new BusinessException("该歌曲为 VIP 专属或需单独购买，当前账号无播放权益（无绿钻无法下载）。");
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
    public String resolveAudioUrl(String url) {
        String songMid = extractSongMid(url);
        String cached = urlCache.get(songMid);
        if (cached != null) {
            return cached;
        }
        parse(url);
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
        m = SONG_DETAIL_MID.matcher(url);
        if (m.find()) {
            return m.group(1); // y.qq.com/n/ryqq(_v2)/songDetail/{songmid} 路径形式
        }
        throw new BusinessException("无法从链接中识别歌曲 ID");
    }

    private String extractSongMid(String url) {
        Matcher m = SONG_MID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = SONG_DETAIL_MID.matcher(url);
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

    private JsonNode fetchSongInfo(String songIdOrMid) {
        // 数字 id 用 songid；songmid（字母数字混合）必须走 songmid 参数，
        // 否则接口会静默返回无关的默认歌曲
        boolean numeric = !songIdOrMid.isBlank()
                && songIdOrMid.chars().allMatch(Character::isDigit);
        String idParam = (numeric ? "songid=" : "songmid=")
                + URLEncoder.encode(songIdOrMid, StandardCharsets.UTF_8);
        String api = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
                + "?" + idParam
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
     * 取歌单某一页的歌曲列表（musicu.fcg uniform_get_Dissinfo，公开歌单匿名可访问）。
     */
    private JsonNode fetchPlaylistPage(long dissid, int begin, int num) {
        String body = "{\"req_0\":{\"module\":\"music.srfDissInfo.aiDissInfo\","
                + "\"method\":\"uniform_get_Dissinfo\",\"param\":{\"disstid\":" + dissid
                + ",\"userinfo\":1,\"song_begin\":" + begin + ",\"song_num\":" + num + "}}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg"))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode data = mapper.readTree(resp.body()).path("req_0").path("data");
            if (data.path("code").asInt(-1) != 0) {
                throw new BusinessException("获取 QQ 音乐歌单信息失败："
                        + data.path("msg").asText("歌单不存在或未公开"));
            }
            return data;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取 QQ 音乐歌单信息失败：" + e.getMessage());
        }
    }
}
