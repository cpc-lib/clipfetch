package com.fvd.parser.application.parser.music;

import com.fvd.parser.application.ParseContext;
import com.fvd.parser.application.parser.AbstractVideoParser;
import com.fvd.parser.application.parser.CookiePolicy;
import com.fvd.parser.application.parser.TempLinkService;
import com.fvd.parser.infrastructure.service.YtDlpService;
import com.fvd.parser.infrastructure.sidecar.KugouMusicBrowserSidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.parser.domain.FormatInfo;
import com.fvd.parser.domain.Platform;
import com.fvd.parser.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
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
 * 酷狗音乐解析：
 * 1. 单曲：抓 mixsong 页面提取 dataFromSmarty（hash、歌名、歌手、时长）；
 *    /song/#id 分享链接中的短 id 即 encode_album_audio_id，归一化为 /mixsong/{id}.html 处理；
 *    /song/#<32位hash> 链接（搜索结果保存到 temp 的链接）直接以 hash 走接口
 * 2. 走移动端 getSongInfo 接口取可播放直链（sharefs CDN，不校验 Referer）
 * 3. 付费/VIP 歌曲匿名接口返回空地址，回退浏览器 sidecar（页面 H5 签名 + 扫码登录态）
 * 4. 搜索页（search.html#searchKeyWord=xxx）：songsearch 接口取歌曲列表，标记免费/VIP/付费
 */
@Slf4j
@Service
public class KugouMusicParser extends AbstractVideoParser {

    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";
    /** 页面内嵌歌曲信息：var dataFromSmarty = [...],//当前页面歌曲信息 */
    private static final Pattern DATA_FROM_SMARTY =
            Pattern.compile("var dataFromSmarty\\s*=\\s*(\\[.*?\\])\\s*,", Pattern.DOTALL);
    /** /song/#id 分享链接的片段短 id（如 #6vmmwf0d，即 encode_album_audio_id） */
    private static final Pattern FRAGMENT_ID = Pattern.compile("#/?([0-9A-Za-z]{4,32})/?$");
    /** /song/#<hash> 片段为 32 位 hash（搜索结果入库链接） */
    private static final Pattern FRAGMENT_HASH = Pattern.compile("#/?([0-9A-Fa-f]{32})/?");
    private static final Pattern SEARCH_KEYWORD = Pattern.compile("searchKeyWord=([^&]+)");
    private static final Pattern ILLEGAL_FILENAME = Pattern.compile("[\\\\/:*?\"<>|]");

    private final int parseTimeout;
    private final KugouMusicBrowserSidecar sidecar;
    private final TempLinkService tempLinkService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 元信息缓存：hash → [标题, 歌手, 扩展名]，供下载时生成文件名 */
    private final Map<String, String[]> metaCache = new ConcurrentHashMap<>();

    public KugouMusicParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                            KugouMusicBrowserSidecar sidecar,
                            TempLinkService tempLinkService) {
        this.parseTimeout = parseTimeout;
        this.sidecar = sidecar;
        this.tempLinkService = tempLinkService;
    }

    @Override
    public Platform platform() {
        return Platform.KUGOU;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    // ===== 搜索页 =====

    /** 搜索结果：子链接（hash 片段链接）、标题、付费类型 0=免费 1=VIP 2=付费 */
    public record SearchSong(String url, String title, Integer vip) {}

    public record SearchResult(String keyword, List<SearchSong> songs) {}

    public boolean isSearchUrl(String url) {
        return supports(url) && url.contains("search.html") && SEARCH_KEYWORD.matcher(url).find();
    }

    /** 搜索页解析：songsearch 接口取歌曲列表，构造 hash 片段链接，按 PayType 标记免费/VIP/付费 */
    public SearchResult parseSearch(String url) {
        Matcher m = SEARCH_KEYWORD.matcher(url);
        if (!m.find()) {
            throw new BusinessException("搜索链接中未找到关键词");
        }
        String keyword = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        String api = "https://songsearch.kugou.com/song_search_v2?keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&page=1&pagesize=30&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0";
        JsonNode root = fetchJson(api, UA_PC);
        JsonNode lists = root.path("data").path("lists");
        if (!lists.isArray()) {
            throw new BusinessException("酷狗搜索接口返回异常");
        }
        List<SearchSong> songs = new ArrayList<>();
        for (JsonNode s : lists) {
            String hash = s.path("FileHash").asText("");
            if (hash.isBlank()) {
                continue;
            }
            String name = stripEm(s.path("SongName").asText(s.path("OriSongName").asText("")));
            String singer = stripEm(s.path("SingerName").asText(""));
            String title = singer.isBlank() ? name : singer + " - " + name;
            // PayType: 0=免费，3=付费专辑，其余按 VIP 处理
            int payType = s.path("PayType").asInt(0);
            Integer vip = payType == 0 ? 0 : (payType == 3 ? 2 : 1);
            songs.add(new SearchSong("https://www.kugou.com/song/#" + hash, title, vip));
        }
        log.info("酷狗搜索解析成功: 关键词={}, 歌曲数={}", keyword, songs.size());
        return new SearchResult(keyword, songs);
    }

    // ===== 单曲 =====

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        if (isSearchUrl(url)) {
            SearchResult result = parseSearch(url);
            int[] counts = tempLinkService.saveTempLinks(
                    result.songs().stream()
                            .map(s -> new TempLinkService.TempSongInput(s.url(), s.title(), s.vip()))
                            .toList(), url);
            return new VideoInfo(null, result.keyword(), null, null, null,
                    null, Platform.KUGOU.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条");
        }
        return parse(url);
    }

    public VideoInfo parse(String url) {
        String hash = extractHashFromFragment(url);
        JsonNode song = null;
        if (hash == null) {
            url = normalizeUrl(url);
            song = fetchPageSong(url);
            hash = song.path("hash").asText("");
            if (hash.isBlank()) {
                throw new BusinessException("页面中未找到歌曲 hash，链接可能不是歌曲播放页");
            }
        }
        JsonNode play = fetchPlayInfo(hash);
        String playUrl = play.path("url").asText("");
        long filesize;
        long bitRate;
        JsonNode sidecarData = null;
        if (playUrl.isBlank()) {
            // 匿名移动端接口拿不到（付费/VIP）：回退浏览器 sidecar（页面签名 + 扫码登录态）
            // hash 片段链接直接传 sidecar（sidecar 从片段提取 hash 签名）
            JsonNode data = sidecar.resolve(url).path("data");
            sidecarData = data;
            playUrl = data.path("play_url").asText("");
            if (playUrl.isBlank()) {
                throw new BusinessException("该歌曲为付费/VIP 歌曲，当前酷狗账号无播放权益"
                        + "（请在「Cookies」弹窗中点击酷狗音乐「扫码登录」，登录 VIP 账号后重试）。");
            }
            // 试听片段判定看 is_free_part：1=返回的是试听片段（未付费），0=完整版（已付费）
            // 注意 trans_param.hash_offset 固定存在（总是 end_ms=60000 试听元数据），不能作为判断依据
            if (data.path("is_free_part").asInt(0) == 1) {
                JsonNode offset = data.path("trans_param").path("hash_offset");
                long clipEndMs = offset.path("end_ms").asLong(0);
                long totalMs = data.path("timelength").asLong(0);
                throw new BusinessException("该歌曲为付费歌曲，当前账号只能获取试听片段（约"
                        + (clipEndMs / 1000) + "秒/" + (totalMs / 1000) + "秒），"
                        + "完整版需购买专辑，VIP 会员同样无法下载完整版。");
            }
            filesize = data.path("filesize").asLong(0);
            bitRate = data.path("bitrate").asLong(0);
            log.info("酷狗音乐经浏览器 sidecar 取到直链: {} ({}kbps)", meta(song, "song_name", ""), bitRate);
        } else {
            filesize = play.path("fileSize").asLong(0);
            bitRate = play.path("bitRate").asLong(0);
        }

        String songName, singer, ext, cover;
        long durationSec;
        if (sidecarData != null) {
            // sidecar 返回完整元信息：audio_name="歌手 - 歌名" 或 song_name+author_name
            String audioName = sidecarData.path("audio_name").asText("");
            if (!audioName.isBlank() && audioName.contains(" - ")) {
                int sep = audioName.indexOf(" - ");
                singer = audioName.substring(0, sep).trim();
                songName = audioName.substring(sep + 3).trim();
            } else {
                songName = sidecarData.path("song_name").asText(sidecarData.path("audio_name").asText("未知歌曲"));
                singer = sidecarData.path("author_name").asText("未知歌手");
            }
            cover = sidecarData.path("img").asText(sidecarData.path("sizable_cover").asText(""));
            durationSec = sidecarData.path("timelength").asLong(0) / 1000;
            ext = extFromUrl(playUrl);
        } else {
            songName = meta(song, "song_name", play.path("songName").asText("未知歌曲"));
            singer = meta(song, "author_name", play.path("singerName").asText("未知歌手"));
            ext = play.path("extName").asText("");
            if (ext.isBlank()) {
                ext = extFromUrl(playUrl);
            }
            durationSec = play.path("timeLength").asLong(0);
            if (durationSec <= 0 && song != null) {
                durationSec = song.path("timelength").asLong(0) / 1000;
            }
            cover = play.path("album_img").asText("");
            if (cover.isBlank()) {
                cover = play.path("imgUrl").asText("");
            }
        }
        if (!cover.isBlank()) {
            cover = cover.replace("{size}", "480");
        }
        metaCache.put(hash, new String[]{songName, singer, ext});

        String label = bitRate > 0 ? ("高品质 " + bitRate + "kbps") : "标准品质";
        List<FormatInfo> formats = List.of(new FormatInfo(
                "kugou_audio", ext, null, null,
                filesize > 0 ? filesize : null, null,
                "none", "audio", label, false, true, true));

        log.info("酷狗音乐解析成功: {} - {} ({}kbps, {}bytes)", singer, songName, bitRate, filesize);
        return new VideoInfo(
                hash,
                songName,
                cover.isBlank() ? null : cover,
                durationSec > 0 ? durationSec : null,
                durationSec > 0 ? YtDlpService.formatDuration(durationSec) : null,
                singer,
                Platform.KUGOU.display,
                null,
                null,
                formats,
                null,
                List.of(),
                false,
                null
        );
    }

    /**
     * 下载时重新取播放地址（sharefs 直链带时间戳会过期）；匿名接口拿不到时回退浏览器 sidecar。
     */
    public String resolveAudioUrl(String url) {
        String hash = extractHashFromFragment(url);
        JsonNode song = null;
        if (hash == null) {
            url = normalizeUrl(url);
            song = fetchPageSong(url);
            hash = song.path("hash").asText("");
            if (hash.isBlank()) {
                throw new BusinessException("页面中未找到歌曲 hash，链接可能不是歌曲播放页");
            }
        }
        JsonNode play = fetchPlayInfo(hash);
        String playUrl = play.path("url").asText("");
        if (playUrl.isBlank()) {
            // 付费/VIP：回退浏览器 sidecar
            JsonNode data = sidecar.resolve(url).path("data");
            playUrl = data.path("play_url").asText("");
            if (playUrl.isBlank()) {
                throw new BusinessException("该歌曲为付费/VIP 歌曲，当前酷狗账号无播放权益"
                        + "（请在「Cookies」弹窗中点击酷狗音乐「扫码登录」，登录 VIP 账号后重试）。");
            }
            // 试听片段判定看 is_free_part：1=试听片段（未付费），0=完整版（已付费）
            if (data.path("is_free_part").asInt(0) == 1) {
                JsonNode offset = data.path("trans_param").path("hash_offset");
                long clipEndMs = offset.path("end_ms").asLong(0);
                long totalMs = data.path("timelength").asLong(0);
                throw new BusinessException("该歌曲为付费歌曲，当前账号只能获取试听片段（约"
                        + (clipEndMs / 1000) + "秒/" + (totalMs / 1000) + "秒），"
                        + "完整版需购买专辑，VIP 会员同样无法下载完整版。");
            }
            // 元信息：页面 > sidecar（audio_name="歌手 - 歌名"）；拿不到时保留已有缓存，不用空值覆盖
            String songName = meta(song, "song_name", "");
            String singer = meta(song, "author_name", "");
            String audioName = data.path("audio_name").asText("");
            if ((songName.isBlank() || singer.isBlank()) && !audioName.isBlank() && audioName.contains(" - ")) {
                int sep = audioName.indexOf(" - ");
                if (singer.isBlank()) {
                    singer = audioName.substring(0, sep).trim();
                }
                if (songName.isBlank()) {
                    songName = audioName.substring(sep + 3).trim();
                }
            }
            if (songName.isBlank()) {
                songName = data.path("song_name").asText("");
            }
            if (singer.isBlank()) {
                singer = data.path("author_name").asText("");
            }
            String[] old = metaCache.get(hash);
            metaCache.put(hash, new String[]{
                    songName.isBlank() ? (old != null ? old[0] : "未知歌曲") : songName,
                    singer.isBlank() ? (old != null ? old[1] : "未知歌手") : singer,
                    extFromUrl(playUrl)});
            return playUrl;
        }
        // 顺带刷新元信息缓存
        String songName = meta(song, "song_name", play.path("songName").asText("未知歌曲"));
        String singer = meta(song, "author_name", play.path("singerName").asText("未知歌手"));
        String ext = play.path("extName").asText("mp3");
        metaCache.put(hash, new String[]{songName, singer, ext});
        return playUrl;
    }

    /** 生成下载文件名：歌手 - 标题.扩展名，并清理 Windows 非法字符 */
    public String resolveFilename(String url) {
        String hash = extractHashFromFragment(url);
        JsonNode song = null;
        if (hash == null) {
            song = fetchPageSong(normalizeUrl(url));
            hash = song.path("hash").asText("");
        }
        String[] cached = metaCache.get(hash);
        String title;
        String singer;
        String ext;
        if (cached != null) {
            title = cached[0];
            singer = cached[1];
            ext = cached[2];
        } else if (song != null) {
            title = song.path("song_name").asText("kugou");
            singer = song.path("author_name").asText("");
            ext = "mp3";
        } else {
            // hash 片段链接无页面元信息，走移动端接口
            JsonNode play = fetchPlayInfo(hash);
            title = play.path("songName").asText("kugou");
            singer = play.path("singerName").asText("");
            ext = play.path("extName").asText("mp3");
        }
        String name = (singer == null || singer.isBlank()) ? title : singer + " - " + title;
        return ILLEGAL_FILENAME.matcher(name).replaceAll("_") + "." + ext;
    }

    // ===== 内部实现 =====

    /** 从 URL 片段提取 32 位歌曲 hash（temp 入库的搜索链接形式）；无则返回 null */
    private static String extractHashFromFragment(String url) {
        int i = url.indexOf('#');
        if (i < 0) {
            return null;
        }
        Matcher m = FRAGMENT_HASH.matcher(url.substring(i));
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** /song/#id 分享链接归一化为 /mixsong/{id}.html（短 id 即 encode_album_audio_id，两者等价） */
    private static String normalizeUrl(String url) {
        if (extractHashFromFragment(url) != null) {
            return url; // 32 位 hash 片段不是短 id，保持原样
        }
        int i = url.indexOf('#');
        if (i < 0) {
            return url;
        }
        Matcher m = FRAGMENT_ID.matcher(url.substring(i));
        if (m.find()) {
            return "https://www.kugou.com/mixsong/" + m.group(1) + ".html";
        }
        return url;
    }

    /** 页面元信息可能为空（hash 片段链接无页面数据），null 安全取值 */
    private static String meta(JsonNode song, String key, String fallback) {
        if (song == null) {
            return fallback;
        }
        String v = song.path(key).asText("");
        return v.isBlank() ? fallback : v;
    }

    /** 搜索结果里的关键词高亮标签（<em>毛不易</em> - 一程山路） */
    private static String stripEm(String s) {
        return s == null ? "" : s.replaceAll("</?em>", "");
    }

    /** 从直链路径推断扩展名（如 .../xxx.mp3），失败默认 mp3 */
    private static String extFromUrl(String url) {
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && path.length() - dot <= 6) {
            String ext = path.substring(dot + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{2,5}")) {
                return ext;
            }
        }
        return "mp3";
    }

    private JsonNode fetchJson(String api, String ua) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", ua)
                    .GET().build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new BusinessException("请求酷狗接口失败：" + e.getMessage());
        }
    }

    /** 抓 mixsong 页面，提取 dataFromSmarty 中第一首歌曲信息 */
    private JsonNode fetchPageSong(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA_PC)
                    .GET().build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher m = DATA_FROM_SMARTY.matcher(resp.body());
            if (!m.find()) {
                throw new BusinessException("页面中未找到歌曲信息，链接可能不是歌曲播放页");
            }
            JsonNode arr = mapper.readTree(m.group(1));
            if (!arr.isArray() || arr.size() == 0) {
                throw new BusinessException("页面中未找到歌曲信息，链接可能不是歌曲播放页");
            }
            return arr.get(0);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取酷狗歌曲页面失败：" + e.getMessage());
        }
    }

    /** 移动端 getSongInfo 接口取播放信息（免费歌曲匿名可用） */
    private JsonNode fetchPlayInfo(String hash) {
        String api = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA_MOBILE)
                    .GET().build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new BusinessException("获取酷狗播放地址失败：" + e.getMessage());
        }
    }
}
