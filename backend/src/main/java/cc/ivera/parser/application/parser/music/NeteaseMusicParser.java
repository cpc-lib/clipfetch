package cc.ivera.parser.application.parser.music;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.application.parser.TempLinkService;
import cc.ivera.parser.infrastructure.service.YtDlpService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cc.ivera.cookie.application.CookieService;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
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
public class NeteaseMusicParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern SONG_ID = Pattern.compile("[?&]id=(\\d+)");
    private static final Pattern ARTIST_ID = Pattern.compile("artist[?&]id=(\\d+)");
    private static final String COVER_TPL = "https://p1.music.126.net/%s.jpg";

    private final int parseTimeout;
    private final TempLinkService tempLinkService;
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
    /** 付费类型缓存：songid → fee（0=免费 1=VIP 4=付费专辑 8=低音质免费） */
    private final Map<String, Integer> feeCache = new ConcurrentHashMap<>();

    public NeteaseMusicParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                              TempLinkService tempLinkService) {
        this.parseTimeout = parseTimeout;
        this.tempLinkService = tempLinkService;
    }

    @Override
    public Platform platform() {
        return Platform.NETEASE_MUSIC;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.OPTIONAL;
    }

    /** 是否为网易云歌手页（如 https://music.163.com/#/artist?id=3684） */
    public boolean isArtistUrl(String url) {
        return supports(url) && url != null && url.contains("/artist");
    }

    /** 是否为网易云歌单页（如 https://music.163.com/#/my/m/music/playlist?id=13828064028） */
    public boolean isPlaylistUrl(String url) {
        return supports(url) && url != null && url.contains("/playlist");
    }

    /** 是否为网易云专辑页（如 https://music.163.com/#/album?id=3438282） */
    public boolean isAlbumUrl(String url) {
        return supports(url) && url != null && url.contains("/album");
    }

    /**
     * 解析专辑页，返回专辑名及其歌曲子链接列表。
     */
    public AlbumParseResult parseAlbum(String url, String cookies) {
        cookies = toHeader(cookies);
        String albumId = extractSongId(url); // 专辑也用 id 参数
        JsonNode root = fetchAlbumInfo(albumId, cookies);
        String albumName = root.path("album").path("name").asText("未知专辑");
        // 专辑歌曲在 album.songs 节点下
        JsonNode songs = root.path("album").path("songs");
        List<ArtistSong> result = new ArrayList<>();
        if (songs.isArray()) {
            for (JsonNode s : songs) {
                String songId = s.path("id").asText("");
                if (songId.isBlank()) continue;
                String name = s.path("name").asText("未知歌曲");
                int vip = feeToVip(s.path("fee").asInt(0));
                String songUrl = "https://music.163.com/#/song?id=" + songId;
                result.add(new ArtistSong(songId, name, songUrl, vip));
            }
        }
        log.info("网易云专辑 {} ({}) 解析到 {} 首歌曲", albumId, albumName, result.size());
        return new AlbumParseResult(albumName, result);
    }

    /** 专辑解析结果：专辑名 + 歌曲列表 */
    public record AlbumParseResult(String albumName, List<ArtistSong> songs) {}

    /**
     * 从网易云官方 API 获取专辑详情及歌曲列表。
     */
    private JsonNode fetchAlbumInfo(String albumId, String cookies) {
        String api = "https://music.163.com/api/album/" + albumId;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/");
            if (cookies != null && !cookies.isBlank()) {
                builder.header("Cookie", cookies);
            }
            HttpResponse<String> resp = client.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(resp.body());
            int code = root.path("code").asInt();
            if (code != 200) {
                throw new BusinessException("获取网易云音乐专辑信息失败：code=" + code
                        + "（专辑不存在或 cookies 已失效）");
            }
            return root;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取网易云音乐专辑信息失败：" + e.getMessage());
        }
    }

    /**
     * 解析歌单页，返回歌单名及其歌曲子链接列表。
     * 个人歌单（#/my/m/music/playlist）需登录 cookies 才能访问，否则接口返回 code=20001。
     */
    public PlaylistParseResult parsePlaylist(String url, String cookies) {
        cookies = toHeader(cookies);
        String playlistId = extractSongId(url); // 歌单也用 id 参数
        JsonNode root = fetchPlaylistInfo(playlistId, cookies);
        // v6 接口返回 playlist，旧接口返回 result，两者兼容
        JsonNode detail = root.has("playlist") ? root.path("playlist") : root.path("result");
        String playlistName = detail.path("name").asText("未知歌单");
        JsonNode tracks = detail.path("tracks");
        List<ArtistSong> songs = new ArrayList<>();
        if (tracks.isArray()) {
            for (JsonNode s : tracks) {
                String songId = s.path("id").asText("");
                if (songId.isBlank()) continue;
                String name = s.path("name").asText("未知歌曲");
                int vip = feeToVip(s.path("fee").asInt(0));
                String songUrl = "https://music.163.com/#/song?id=" + songId;
                songs.add(new ArtistSong(songId, name, songUrl, vip));
            }
        }
        log.info("网易云歌单 {} ({}) 解析到 {} 首歌曲", playlistId, playlistName, songs.size());
        return new PlaylistParseResult(playlistName, songs);
    }

    /** 歌单解析结果：歌单名 + 歌曲列表 */
    public record PlaylistParseResult(String playlistName, List<ArtistSong> songs) {}

    /**
     * 从网易云官方 API 获取歌单详情及歌曲列表。
     * 用 v6 接口并带 cookies（个人歌单需登录态）。
     */
    private JsonNode fetchPlaylistInfo(String playlistId, String cookies) {
        String api = "https://music.163.com/api/v6/playlist/detail?id=" + playlistId + "&n=1000";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/");
            if (cookies != null && !cookies.isBlank()) {
                builder.header("Cookie", cookies);
            }
            HttpResponse<String> resp = client.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(resp.body());
            int code = root.path("code").asInt();
            if (code != 200) {
                // 20001=歌单不存在或无权限（个人歌单未带 cookies）
                if (code == 20001 && (cookies == null || cookies.isBlank())) {
                    throw new BusinessException("该歌单可能是个人歌单或未公开，请先在 Cookie 管理中上传网易云音乐 cookies 后重试");
                }
                throw new BusinessException("获取网易云音乐歌单信息失败：code=" + code
                        + "（歌单不存在、未公开或 cookies 已失效）");
            }
            return root;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取网易云音乐歌单信息失败：" + e.getMessage());
        }
    }

    /**
     * 解析歌手页，返回歌手名及其热门歌曲子链接列表。
     */
    public ArtistParseResult parseArtist(String url) {
        String artistId = extractArtistId(url);
        JsonNode root = fetchArtistInfo(artistId);
        String artistName = root.path("artist").path("name").asText(null);
        JsonNode songs = root.path("hotSongs");
        if (!songs.isArray() || songs.size() == 0) {
            songs = root.path("songs");
        }
        List<ArtistSong> result = new ArrayList<>();
        if (songs.isArray()) {
            for (JsonNode s : songs) {
                String songId = s.path("id").asText("");
                if (songId.isBlank()) continue;
                String name = s.path("name").asText("未知歌曲");
                int vip = feeToVip(s.path("fee").asInt(0));
                String songUrl = "https://music.163.com/#/song?id=" + songId;
                result.add(new ArtistSong(songId, name, songUrl, vip));
            }
        }
        log.info("网易云歌手 {} ({}) 解析到 {} 首歌曲", artistId, artistName, result.size());
        return new ArtistParseResult(artistName, result);
    }

    /** 歌手解析结果：歌手名 + 热门歌曲列表 */
    public record ArtistParseResult(String artistName, List<ArtistSong> songs) {}

    /** 集合页歌曲子链接；vip 付费类型：0=免费 1=VIP 会员 2=付费（需单独购买） */
    public record ArtistSong(String songId, String title, String url, int vip) {}

    /**
     * 网易云 fee → 统一付费类型：
     * 1=VIP 会员歌曲 → 1；4=付费专辑/单曲（需单独购买，会员也不可下载）→ 2；
     * 0=免费、8=低音质免费 → 0。
     */
    private static int feeToVip(int fee) {
        return switch (fee) {
            case 1 -> 1;
            case 4 -> 2;
            default -> 0;
        };
    }


    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String cookies = ctx.cookies();
        if (isArtistUrl(url)) {
            ArtistParseResult result = parseArtist(url);
            int[] counts = saveSongsToTemp(result.songs(), url);
            return new VideoInfo(null, result.artistName(), null, null, null,
                    null, Platform.NETEASE_MUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条");
        }
        if (isPlaylistUrl(url)) {
            PlaylistParseResult result = parsePlaylist(url, cookies);
            int[] counts = saveSongsToTemp(result.songs(), url);
            return new VideoInfo(null, result.playlistName(), null, null, null,
                    null, Platform.NETEASE_MUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条");
        }
        if (isAlbumUrl(url)) {
            AlbumParseResult result = parseAlbum(url, cookies);
            int[] counts = saveSongsToTemp(result.songs(), url);
            return new VideoInfo(null, result.albumName(), null, null, null,
                    null, Platform.NETEASE_MUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条");
        }
        return parse(url, cookies);
    }

    private int[] saveSongsToTemp(List<ArtistSong> songs, String sourceUrl) {
        return tempLinkService.saveTempLinks(
                songs.stream()
                        .map(s -> new TempLinkService.TempSongInput(s.url(), s.title(), s.vip()))
                        .toList(), sourceUrl);
    }

    public VideoInfo parse(String url, String cookies) {
        cookies = toHeader(cookies);
        String songId = extractSongId(url);
        JsonNode song = fetchSongInfo(songId);
        String title = song.path("name").asText("未知歌曲");
        String artist = extractArtist(song);
        // 缓存标题和歌手用于下载时生成文件名
        titleCache.put(songId, title);
        artistCache.put(songId, artist);
        int fee = song.path("fee").asInt(0);
        feeCache.put(songId, fee);
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
                buildDescription(albumName, fee)
        );
    }

    /** 组装描述：专辑信息 + 付费类型提示（1=VIP 歌曲，4=付费专辑需单独购买） */
    private String buildDescription(String albumName, int fee) {
        String feeHint = switch (fee) {
            case 1 -> "VIP 歌曲";
            case 4 -> "付费歌曲（需单独购买）";
            default -> "";
        };
        StringBuilder sb = new StringBuilder();
        if (albumName != null) {
            sb.append("专辑：").append(albumName);
        }
        if (!feeHint.isEmpty()) {
            if (sb.length() > 0) sb.append("｜");
            sb.append(feeHint);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 下载时按所选品质请求官方 weapi 播放直链；缓存 songId:formatId → 直链/扩展名。
     * 若标题/歌手缓存缺失（如重启后），先调 parse 填充元数据缓存。
     */
    public String resolveAudioUrl(String url, String cookies, String formatId) {
        cookies = toHeader(cookies);
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
        String wanted = switch (fid) {
            case "netease_128k" -> "standard";
            case "netease_192k" -> "higher";
            case "netease_320k" -> "exhigh";
            default -> "lossless";
        };

        // 品质回退：所选品质无权限（如 VIP 歌曲/付费专辑）时依次降级 无损→320k→192k→128k
        List<String> levels = switch (wanted) {
            case "lossless" -> List.of("lossless", "exhigh", "higher", "standard");
            case "exhigh" -> List.of("exhigh", "higher", "standard");
            case "higher" -> List.of("higher", "standard");
            default -> List.of("standard");
        };
        String audioUrl = null;
        String actualLevel = wanted;
        for (String lv : levels) {
            audioUrl = fetchPlayUrl(songId, cookieHeader, lv);
            if (audioUrl != null) {
                actualLevel = lv;
                if (!lv.equals(wanted)) {
                    log.info("网易云音乐 {} 品质 {} 无权限，已回退到 {}", songId, wanted, lv);
                }
                break;
            }
        }
        if (audioUrl == null) {
            // 全品质失败：先判断 cookies 登录态是否有效，再结合付费类型给出精确原因
            if (!isCookieValid(cookieHeader)) {
                throw new BusinessException("网易云音乐 cookies 已失效或未登录，请重新上传有效的 cookies 后重试");
            }
            Integer fee = feeCache.get(songId);
            if (fee != null && fee == 4) {
                throw new BusinessException("该歌曲为付费歌曲（付费专辑），需单独购买后才能下载，VIP 会员同样无法直接下载");
            }
            if (fee != null && fee == 1) {
                throw new BusinessException("该歌曲为 VIP 歌曲，请确认 cookies 对应的账号已开通 VIP 会员");
            }
            throw new BusinessException("获取网易云音乐播放地址失败：该歌曲可能需要 VIP/付费购买，或 cookies 权限不足（详见后端日志）");
        }
        String ext = "lossless".equals(actualLevel) ? "flac" : "mp3";
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

    private String extractArtistId(String url) {
        Matcher m = ARTIST_ID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        throw new BusinessException("无法从链接中识别网易云音乐歌手 ID");
    }

    /**
     * 从网易云官方 API 获取歌手信息及热门歌曲
     */
    private JsonNode fetchArtistInfo(String artistId) {
        String api = "https://music.163.com/api/artist/" + artistId;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(resp.body());
            if (root.path("code").asInt() != 200) {
                throw new BusinessException("获取网易云音乐歌手信息失败：code=" + root.path("code").asInt());
            }
            return root;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取网易云音乐歌手信息失败：" + e.getMessage());
        }
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
                // 完整打印 songData（含 feeType/freeTrialInfo 等），便于区分 VIP 限制/付费专辑/风控
                log.warn("网易云音乐 weapi 无可用地址: songId={}, level={}, songData={}", songId, level, songData);
                return null;
            }
            return url;
        } catch (Exception e) {
            log.warn("网易云音乐取播放地址失败 ({}): {}", songId, e.getMessage());
            return null;
        }
    }

    /**
     * 校验 cookies 登录态是否有效：调官方 weapi 账号接口，返回 account 非空即为已登录。
     * 接口异常时按"无法确认"处理（视为有效，避免误伤），仅用于细化报错文案。
     */
    private boolean isCookieValid(String cookies) {
        if (cookies == null || cookies.isBlank() || !cookies.contains("MUSIC_U=")) {
            return false;
        }
        String csrf = extractCsrf(cookies);
        if (csrf == null) {
            return false;
        }
        try {
            String[] encrypted = NeteaseCrypto.encrypt("{\"csrf_token\":\"" + csrf + "\"}");
            String body = "params=" + URLEncoder.encode(encrypted[0], StandardCharsets.UTF_8)
                    + "&encSecKey=" + encrypted[1];
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://music.163.com/weapi/w/nuser/account/get?csrf_token=" + csrf))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookies)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(resp.body());
            boolean loggedIn = root.path("account") != null && !root.path("account").isNull();
            log.info("网易云音乐登录态校验: loggedIn={}, code={}", loggedIn, root.path("code").asInt());
            return loggedIn;
        } catch (Exception e) {
            log.warn("网易云音乐登录态校验异常，按有效处理: {}", e.getMessage());
            return true;
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

    /**
     * 统一 cookie 格式：库存为 Netscape cookies.txt 原文，转成 "k=v; k=v" 请求头格式；
     * 已是请求头格式（不含制表符/换行）则原样返回。
     */
    private String toHeader(String cookies) {
        if (cookies == null || cookies.isBlank()) {
            return cookies;
        }
        if (cookies.contains("\t") || cookies.contains("\n") || cookies.contains("\r")) {
            return CookieService.toCookieHeader(cookies, Platform.NETEASE_MUSIC);
        }
        return cookies;
    }
}
