package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.DownloadService;
import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import cc.ivera.parser.infrastructure.sidecar.KubbBrowserSidecar;
import cc.ivera.shared.web.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷播（kubb.tv / ify.tv，播放页 {@code /play/{剧集key}?id={集数key}}）解析器。
 *
 * <p>播放页 SSR 对服务端请求不稳定（Cloudflare 拦截），实际数据走 JSON 接口：
 * <pre>
 * GET https://m10.kubb.tv/v3/video/detail            剧集元信息（标题、封面、cid、总集数）
 * GET https://m10.kubb.tv/v3/video/languagesplaylist 分集列表（仅 URL 无 id 时用于取第一集）
 * GET https://m10.kubb.tv/v3/video/play              播放信息（clarity 档位 + HLS 直链）
 * </pre>
 * 所有接口都必须带 {@code vv}/{@code pub} 签名：
 * {@code vv = md5(publicKey + "&" + lowercase(query) + "&" + privateKey)}，其中
 * publicKey/privateKey 来自首页 SSR 注入的 {@code pConfig}（会轮换，因此每次实时抓取）。
 *
 * <p>清晰度：576p 游客可用；720p / 1080p 为 VIP 专属（{@code isVIP=true}），
 * 游客请求也会被 CDN 降级到 576p。VIP 需通过 {@link KubbBrowserSidecar} 手动登录一次，
 * 登录 token（uid/expire/gid/sign/token）会被拼进接口请求参数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IfyTvParser extends AbstractVideoParser {

    private static final String SITE = "https://www.kubb.tv";
    private static final String API = "https://m10.kubb.tv/v3";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DEFAULT_CID = "0,1,4,151";
    private static final Pattern PLAY_PATH = Pattern.compile("^/play/([A-Za-z0-9]+)$");
    private static final Pattern P_CONFIG = Pattern.compile(
            "\"pConfig\":\\{\"publicKey\":\"([^\"]+)\",\"privateKey\":\\[\"([^\"]+)\"\\]");

    private static final Map<String, String> RESOLUTIONS = Map.of(
            "1080", "1920x1080",
            "720", "1280x720",
            "576", "1024x576");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final DownloadService downloadService;
    private final KubbBrowserSidecar kubbBrowserSidecar;

    @Override
    public Platform platform() {
        return null;
    }

    @Override
    public boolean supports(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("kubb.tv") || host.endsWith(".kubb.tv")
                    || host.equals("ify.tv") || host.endsWith(".ify.tv");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        EpisodeRef ref = EpisodeRef.from(ctx.url());
        SignKey key = loadSignKey();
        String cid = DEFAULT_CID;
        JsonNode detail = null;
        try {
            detail = detail(ref.seriesKey(), key);
            String cidValue = detail.path("cid").asText("");
            if (!cidValue.isBlank()) {
                cid = cidValue;
            }
        } catch (BusinessException e) {
            log.warn("酷播详情接口失败，继续按播放接口解析: {}", e.getMessage());
        }

        String episodeKey = ref.episodeKey();
        if (episodeKey == null) {
            List<JsonNode> episodes = episodes(ref.seriesKey(), cid, key);
            if (episodes.isEmpty()) {
                throw new BusinessException("未获取到酷播剧集列表，请确认链接可播放");
            }
            episodeKey = episodes.get(0).path("key").asText();
        }

        JsonNode play = play(episodeKey, null, key);
        String episodeName = play.path("mediaTitle").asText("");
        String seriesTitle = detail != null ? detail.path("title").asText("") : "";
        String title = (seriesTitle + " " + episodeName).trim();
        if (title.isBlank()) {
            title = "酷播视频";
        }
        String thumbnail = detail != null ? detail.path("imgPath").asText(null) : null;
        Long duration = play.path("customData").path("t").asLong(0);
        if (duration != null && duration <= 0) {
            duration = null;
        }

        List<FormatInfo> formats = new ArrayList<>();
        for (JsonNode c : play.path("clarity")) {
            String label = c.path("title").asText("");
            if (label.isBlank()) {
                continue;
            }
            boolean vip = c.path("isVIP").asBoolean(false);
            String name = label + "p " + c.path("description").asText("") + (vip ? "（VIP）" : "");
            formats.add(new FormatInfo(c.path("key").asText(), "mp4",
                    RESOLUTIONS.getOrDefault(label, null), parseInt(label),
                    null, null, "h264", "aac", name, false, false, true));
        }
        formats.sort(Comparator.comparingInt((FormatInfo f) -> f.height() == null ? 0 : f.height()).reversed());
        if (formats.isEmpty()) {
            throw new BusinessException("酷播未返回可用清晰度，请稍后重试");
        }
        boolean vipOnly = formats.stream().anyMatch(f -> f.label() != null && f.label().contains("VIP"));
        String notice = vipOnly
                ? "720p / 1080p 为酷播 VIP 专属，需在「我的 Cookies → 酷播」完成 VIP 登录后再下载"
                : null;

        return new VideoInfo(episodeKey, title, thumbnail, duration, null, null, "酷播",
                null, null, formats, null, List.of(), false, null, notice);
    }

    /**
     * 在线播放取流：按所选清晰度重新取 HLS 直链；VIP 清晰度需要 sidecar 已登录 token。
     */
    public String resolveStreamUrl(String url, String formatId) {
        EpisodeRef ref = EpisodeRef.from(url);
        String episodeKey = ref.episodeKey();
        SignKey key = loadSignKey();
        if (episodeKey == null) {
            List<JsonNode> episodes = episodes(ref.seriesKey(), DEFAULT_CID, key);
            if (episodes.isEmpty()) {
                throw new BusinessException("未获取到酷播剧集列表，请确认链接可播放");
            }
            episodeKey = episodes.get(0).path("key").asText();
        }

        int requestedHeight = 0;
        if (formatId != null && !formatId.isBlank()) {
            JsonNode clarity = findClarity(episodeKey, formatId, key);
            if (clarity != null) {
                requestedHeight = parseInt(clarity.path("title").asText("0"));
            }
        }
        JsonNode token = requestedHeight > 576 ? kubbBrowserSidecar.token() : null;
        if (requestedHeight > 576 && token == null) {
            throw new BusinessException("酷播 " + requestedHeight + "p 为 VIP 专属，请先在「我的 Cookies → 酷播」完成 VIP 登录（会弹出 Chrome 窗口）");
        }

        JsonNode play = play(episodeKey, formatId, key, token);
        int actualHeight = parseInt(play.path("playingMedia").path("title").asText("0"));
        if (requestedHeight > actualHeight && requestedHeight > 576) {
            throw new BusinessException("酷播当前登录态未获得 " + requestedHeight
                    + "p 清晰度（实际 " + actualHeight + "p），请确认账号是 VIP 后重新登录");
        }
        for (JsonNode p : play.path("flvPathList")) {
            if (p.path("isHls").asBoolean(false) && !p.path("result").asText("").isBlank()) {
                String streamUrl = p.path("result").asText();
                log.info("酷播取流: episode={}, 清晰度={}p, 实际={}p",
                        episodeKey, requestedHeight, actualHeight);
                return streamUrl;
            }
        }
        throw new BusinessException("酷播未返回可下载的视频流，请稍后重试");
    }

    /**
     * 按所选清晰度重新取流并下载。VIP 清晰度需要 sidecar 中已登录的 token，
     * 否则接口会把码率降级到 576p。
     */
    public void download(String url, String formatId, String title,
                         HttpServletResponse response, String taskId) {
        String streamUrl = resolveStreamUrl(url, formatId);
        String name = title != null && !title.isBlank() ? title
                : "kubb-" + EpisodeRef.from(url).episodeKey();
        downloadService.downloadToResponse(streamUrl, null, name, response, null, taskId,
                List.of("-N", "16", "--add-headers", "Referer:https://www.kubb.tv/"));
    }

    // ===== 接口 =====

    private JsonNode detail(String seriesKey, SignKey key) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("id", seriesKey);
        params.put("ispath", "false");
        params.put("cinema", "1");
        params.put("device", "desktop");
        params.put("player", "CkPlayer");
        params.put("tech", "HLS");
        params.put("lang", "cns");
        JsonNode info = apiInfo("/video/detail", params, null, key);
        return info;
    }

    private List<JsonNode> episodes(String seriesKey, String cid, SignKey key) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("cid", cid);
        params.put("cinema", "1");
        params.put("lsk", "1");
        params.put("taxis", "0");
        params.put("vid", seriesKey);
        JsonNode info = apiInfo("/video/languagesplaylist", params, null, key);
        List<JsonNode> list = new ArrayList<>();
        info.path("playList").forEach(list::add);
        return list;
    }

    /**
     * 取某集某个清晰度 key 对应的档位信息（用于确定用户选择的清晰度高度）。
     */
    private JsonNode findClarity(String episodeKey, String formatId, SignKey key) {
        JsonNode play = play(episodeKey, null, key);
        for (JsonNode c : play.path("clarity")) {
            if (formatId.equals(c.path("key").asText())) {
                return c;
            }
        }
        return null;
    }

    private JsonNode play(String id, String formatId, SignKey key) {
        return play(id, formatId, key, null);
    }

    private JsonNode play(String episodeKey, String formatId, SignKey key, JsonNode token) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("cinema", "1");
        params.put("id", formatId != null && !formatId.isBlank() ? formatId : episodeKey);
        params.put("a", "0");
        params.put("lang", "cns");
        params.put("usersign", "1");
        params.put("region", "GL.");
        params.put("device", "1");
        params.put("isMasterSupport", "0");
        return apiInfo("/video/play", params, token, key);
    }

    private JsonNode apiInfo(String path, LinkedHashMap<String, String> params, JsonNode token, SignKey key) {
        JsonNode node = apiGet(path, params, token, key);
        JsonNode data = node.path("data");
        int code = data.path("code").asInt(-1);
        if (code != 0) {
            String msg = data.path("msg").asText("接口返回异常");
            throw new BusinessException("酷播接口失败：" + msg);
        }
        JsonNode info = data.path("info");
        if (!info.isArray() || info.isEmpty()) {
            throw new BusinessException("酷播接口未返回视频信息");
        }
        return info.get(0);
    }

    private JsonNode apiGet(String path, LinkedHashMap<String, String> baseParams, JsonNode token, SignKey key) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>(baseParams);
        params.put("v", "1");
        if (token != null && token.path("uid").asLong(0) > 0) {
            token.fields().forEachRemaining(e -> params.put(e.getKey(),
                    e.getValue().isNull() ? "null" : e.getValue().asText()));
        }
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (raw.length() > 0) {
                raw.append('&');
            }
            raw.append(e.getKey()).append('=').append(e.getValue());
        }
        String vv = md5Hex(key.publicKey() + "&" + raw.toString().toLowerCase(Locale.ROOT) + "&" + key.privateKey());
        String url = API + path + "?" + raw + "&vv=" + vv + "&pub=" + key.publicKey();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", UA)
                    .header("Referer", SITE + "/")
                    .header("Origin", SITE)
                    .header("Accept", "application/json, text/plain, */*")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("酷播接口返回 HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("请求酷播接口失败：" + e.getMessage());
        }
    }

    /**
     * 抓首页 SSR 注入的 pConfig（接口签名密钥，站点会轮换，不能写死）。
     */
    private SignKey loadSignKey() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(SITE + "/"))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher m = P_CONFIG.matcher(resp.body());
            if (!m.find()) {
                throw new BusinessException("酷播首页结构变化，未能获取接口签名配置");
            }
            return new SignKey(m.group(1), m.group(2));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取酷播接口签名配置失败：" + e.getMessage());
        }
    }

    private static String md5Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private record SignKey(String publicKey, String privateKey) {
    }

    /**
     * 播放页 URL：{@code /play/{剧集key}?id={集数key}}，id 可缺省。
     */
    private record EpisodeRef(String seriesKey, String episodeKey) {

        static EpisodeRef from(String url) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (Exception e) {
                throw new BusinessException("酷播链接格式不正确");
            }
            Matcher m = PLAY_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!m.matches()) {
                throw new BusinessException("暂不支持该酷播链接，请使用 /play/ 播放页链接");
            }
            String episodeKey = null;
            String query = uri.getRawQuery();
            if (query != null) {
                for (String kv : query.split("&")) {
                    if (kv.startsWith("id=") && kv.length() > 3) {
                        episodeKey = kv.substring(3);
                    }
                }
            }
            return new EpisodeRef(m.group(1), episodeKey);
        }
    }
}
