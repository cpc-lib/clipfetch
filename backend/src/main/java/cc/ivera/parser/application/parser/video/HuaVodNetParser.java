package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.DownloadService;
import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import cc.ivera.shared.web.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 华视影院（huavod.net）视频解析。
 *
 * <pre>
 * 播放页 URL（/vodplay/204771-1-1.html → 视频ID-线路ID-集数ID）
 *     ↓ GET 播放页（建立会话 cookie）
 * GET /Player/ec?episode=204771-1-1 → 内嵌 let ConFig={...}
 *     ↓ 取 mt（一次性解析令牌）与 ad_duration_ms（站点要求的最低等待时长）
 * 等待 ad_duration_ms - 2000ms 后 POST /Player/resolveUrl（token=mt）
 *     ↓ {code:1, data:{url:"https://huavod.net/link/penny-xxx.m3u8"}}
 * yt-dlp 多连接下载 m3u8 → 合并 MP4
 * </pre>
 *
 * 令牌一次性且过早调用会作废：解析阶段只读元信息（秒级返回），
 * 下载时重新获取播放页与令牌，再按站点要求的广告等待时长发起 resolveUrl。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HuaVodNetParser extends AbstractVideoParser {

    private static final String SITE = "https://huavod.net";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    /** /vodplay/204771-1-1.html → 视频ID、线路ID、集数ID */
    private static final Pattern PLAY_PATH = Pattern.compile("/vodplay/(\\d+)-(\\d+)-(\\d+)\\.html");
    /** 下载格式 ID：ep-线路ID-集数ID */
    private static final Pattern FORMAT_ID = Pattern.compile("ep-(\\d+)-(\\d+)");
    /** 广告等待上限，避免站点异常下发超大 ad_duration_ms 时长时间挂起下载 */
    private static final long MAX_AD_WAIT_MS = 25_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DownloadService downloadService;

    @Override
    public Platform platform() {
        return null;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    @Override
    public boolean supports(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("huavod.net") || host.endsWith(".huavod.net");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        EpisodeRef ref = EpisodeRef.from(ctx.url(), null);
        PlayerPage page = fetchPlayerPage(newClient(), ref);
        String title = page.title() != null && !page.title().isBlank()
                ? page.title() : "华视影院 " + ref.videoId();
        StringBuilder label = new StringBuilder("华视影院");
        if (page.sourceName() != null && !page.sourceName().isBlank()) {
            label.append(" · ").append(page.sourceName());
        }
        if (page.episodeName() != null && !page.episodeName().isBlank()) {
            label.append(' ').append(page.episodeName());
        }
        FormatInfo format = new FormatInfo(ref.formatId(), "mp4", null, null,
                null, null, "h264", "aac", label.toString(), false, false, true);
        return new VideoInfo(String.valueOf(ref.videoId()), title, null, null, null,
                null, "华视影院", null, null, List.of(format), null, List.of(), false);
    }

    public void download(String url, String formatId, String title,
                         HttpServletResponse response, String taskId) {
        if (!supports(url)) {
            throw new BusinessException("不是支持的华视影院链接（请使用 huavod.net 播放页链接）");
        }
        EpisodeRef ref = EpisodeRef.from(url, formatId);
        String streamUrl = resolveStream(ref);
        String name = title != null && !title.isBlank()
                ? title : "huavod-" + ref.videoId() + "-" + ref.nid();
        log.info("华视影院下载: episode={} m3u8={}", ref, streamUrl);
        downloadService.downloadToResponse(streamUrl, null, name, response, null, taskId,
                List.of("-N", "16"));
    }

    /**
     * 取新令牌 → 等待站点要求的广告时长 → resolveUrl，返回带签名的 m3u8 地址。
     * 令牌过早使用会被站点作废，因此失败后重新取页再重试一次。
     */
    private String resolveStream(EpisodeRef ref) {
        HttpClient client = newClient();
        for (int attempt = 1; attempt <= 2; attempt++) {
            PlayerPage page = fetchPlayerPage(client, ref);
            if (page.token() == null || page.token().isBlank()) {
                throw new BusinessException("华视影院播放页未下发解析令牌，请稍后重试");
            }
            sleepAd(page.adDurationMs());
            JsonNode result = postResolve(client, ref, page.token());
            if (result.path("code").asInt() == 1) {
                String streamUrl = result.path("data").path("url").asText("");
                if (!streamUrl.isBlank()) {
                    return streamUrl;
                }
            }
            String msg = result.path("msg").asText("");
            log.warn("华视影院 resolveUrl 失败（第 {} 次）: code={}, msg={}",
                    attempt, result.path("code").asInt(), msg);
            if (attempt == 2) {
                throw new BusinessException("华视影院解析失败"
                        + (msg.isBlank() ? "" : "：" + msg) + "，请稍后重试");
            }
        }
        throw new BusinessException("华视影院解析失败，请稍后重试");
    }

    private PlayerPage fetchPlayerPage(HttpClient client, EpisodeRef ref) {
        String playUrl = SITE + "/vodplay/" + ref.videoId() + "-" + ref.sid() + "-" + ref.nid() + ".html";
        String playerUrl = SITE + "/Player/ec?episode=" + ref.videoId() + "-" + ref.sid() + "-" + ref.nid();
        try {
            HttpResponse<String> play = client.send(
                    request(playUrl, playUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (play.statusCode() != 200) {
                throw new BusinessException("华视影院播放页不可访问（HTTP " + play.statusCode()
                        + "），请确认视频仍在架");
            }
            HttpResponse<String> resp = client.send(
                    request(playerUrl, playUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("获取华视影院播放器页失败（HTTP " + resp.statusCode() + "），请稍后重试");
            }
            JsonNode config = parseConfig(resp.body());
            JsonNode mesData = config.path("MesData");
            if (mesData.path("id").asText("").isBlank()) {
                throw new BusinessException("华视影院未返回剧集信息，请确认播放页链接有效");
            }
            return new PlayerPage(
                    config.path("mt").asText(""),
                    config.path("ad_duration_ms").asInt(0),
                    mesData.path("name").asText(""),
                    sourceName(mesData.path("episodeList"), ref.sid()),
                    episodeName(mesData.path("episodeList"), ref.sid(), ref.nid()));
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("华视影院解析被中断");
        } catch (Exception e) {
            log.warn("华视影院播放页请求失败: {}", e.getMessage());
            throw new BusinessException("获取华视影院播放页失败：" + e.getMessage());
        }
    }

    private JsonNode postResolve(HttpClient client, EpisodeRef ref, String token) {
        String playUrl = SITE + "/vodplay/" + ref.videoId() + "-" + ref.sid() + "-" + ref.nid() + ".html";
        String body = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> resp = client.send(
                    request(SITE + "/Player/resolveUrl", playUrl)
                            .header("Origin", SITE)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("华视影院流地址解析失败（HTTP " + resp.statusCode() + "），请稍后重试");
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("华视影院解析被中断");
        } catch (Exception e) {
            throw new BusinessException("华视影院流地址解析失败：" + e.getMessage());
        }
    }

    /**
     * 播放器页内嵌 {@code let ConFig={...};}：从 "ConFig=" 后按花括号配对截取完整 JSON，
     * 字符串内的花括号与转义字符不参与配对。
     */
    private JsonNode parseConfig(String html) {
        int keyIdx = html.indexOf("ConFig=");
        if (keyIdx < 0) {
            throw new BusinessException("华视影院播放器页结构变化，未找到播放器配置");
        }
        int start = html.indexOf('{', keyIdx);
        if (start < 0) {
            throw new BusinessException("华视影院播放器页结构变化，未找到播放器配置");
        }
        int end = matchClosingBrace(html, start);
        if (end < 0) {
            throw new BusinessException("华视影院播放器页结构变化，播放器配置不完整");
        }
        try {
            return mapper.readTree(html.substring(start, end + 1));
        } catch (Exception e) {
            throw new BusinessException("华视影院播放器配置解析失败，请稍后重试");
        }
    }

    private static int matchClosingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String sourceName(JsonNode episodeList, int sid) {
        for (JsonNode source : episodeList.path("url")) {
            if (source.path("sid").asInt(-1) == sid) {
                return source.path("from").asText("");
            }
        }
        return "";
    }

    private static String episodeName(JsonNode episodeList, int sid, int nid) {
        for (JsonNode source : episodeList.path("url")) {
            if (source.path("sid").asInt(-1) != sid) {
                continue;
            }
            for (JsonNode episode : source.path("url")) {
                if (episode.path("nid").asInt(-1) == nid) {
                    return episode.path("name").asText("");
                }
            }
        }
        return "";
    }

    private static void sleepAd(int adDurationMs) {
        long waitMs = Math.min(Math.max(0L, adDurationMs - 2000L), MAX_AD_WAIT_MS);
        if (waitMs <= 0) {
            return;
        }
        log.info("华视影院广告等待 {} 秒后解析流地址", waitMs / 1000);
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("华视影院解析被中断");
        }
    }

    private static HttpRequest.Builder request(String url, String referer) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", referer);
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(new CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
                .build();
    }

    /**
     * 剧集定位：视频ID 来自播放页路径，线路/集数可被下载格式 ID（ep-线路-集数）覆盖。
     */
    private record EpisodeRef(long videoId, int sid, int nid) {

        static EpisodeRef from(String url, String formatId) {
            Matcher path = PLAY_PATH.matcher(URI.create(url).getPath());
            if (!path.find()) {
                throw new BusinessException("仅支持华视影院播放页链接（形如 /vodplay/204771-1-1.html）");
            }
            long videoId = Long.parseLong(path.group(1));
            int sid = Integer.parseInt(path.group(2));
            int nid = Integer.parseInt(path.group(3));
            if (formatId != null) {
                Matcher format = FORMAT_ID.matcher(formatId);
                if (format.matches()) {
                    sid = Integer.parseInt(format.group(1));
                    nid = Integer.parseInt(format.group(2));
                }
            }
            return new EpisodeRef(videoId, sid, nid);
        }

        String formatId() {
            return "ep-" + sid + "-" + nid;
        }
    }

    private record PlayerPage(String token, int adDurationMs, String title,
                              String sourceName, String episodeName) {
    }
}
