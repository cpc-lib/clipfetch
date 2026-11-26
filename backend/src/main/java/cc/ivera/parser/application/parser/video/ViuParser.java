package cc.ivera.parser.application.parser.video;

import cc.ivera.cookie.application.CookieService;
import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.infrastructure.service.YtDlpService;
import cc.ivera.parser.infrastructure.sidecar.ViuBrowserSidecar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Viu OTT（viu.com）解析。
 * <pre>
 * URL：{cc}={hk/sg/th/ph} {ll}={zh/zh-cn/zh-hk/en-us/en/...}
 *      https://www.viu.com/ott/{cc}/{ll}/vod/{product_id}/{slug}
 *
 * 三步 API 链（api-gateway-global.viu.com 对中国大陆 IP 直连通，无需代理）：
 *   1. GET  /api/mobile?r=/vod/detail&product_id={id}&area_id={...}&language_flag_id={...}
 *          → current_product.ccs_product_id / cover_image_url / synopsis
 *          → series.name / series_id / subtitle[]
 *   2. POST /api/auth/token?v={rand}000  Body: {countryCode, platform, uuid, carrierId, ...}
 *          → Bearer token（JWT，每次解析刷新，有效期足够覆盖后续 distribute 调用）
 *   3. GET  /api/playback/distribute?ccs_product_id={id}&language_flag_id={...}&area_id={...}
 *          Header: Authorization: Bearer {token} + Referer + Origin
 *          → stream.airplayurl.{s240p,s480p,s720p,s1080p}（签名 HLS m3u8）
 *          → stream.size / stream.duration
 * </pre>
 *
 * <p>返回格式为 **带签名的 HLS m3u8**（yt-dlp 2022 年提取器已过时，当时 Viu 返回 MP4 直链，现已重构）。
 * 签名 URL 有 3 小时有效期，airplayurl 带 drm=y 标记但实际是明文 HLS variant 清单，浏览器可直接消费。
 * 为稳妥起见初始 `FormatInfo.serverOnly=true`，后续 CDN 鉴权实测后可放开。
 */
@Slf4j
@Service
public class ViuParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String GATEWAY = "https://api-gateway-global.viu.com";
    private static final String VALID_URL_RE =
            "^https?://(?:www\\.)?viu\\.com/ott/(?<cc>[a-z]{2})/(?<ll>[a-z]{2}-?[a-z]{0,2})/vod/(?<id>\\d+)";
    private static final Pattern URL_RE = Pattern.compile(VALID_URL_RE);
    /** airplayurl 键: s480p → 480 */
    private static final Pattern HEIGHT_RE = Pattern.compile("s(\\d+)p$");

    /** area_id 映射：HK=1 SG=2 TH=4 PH=5，未列出的地区暂不支持 */
    private static final Map<String, Integer> AREA_ID = Map.of("HK", 1, "SG", 2, "TH", 4, "PH", 5);
    /** language_flag_id：zh-cn/zh → 2，zh-hk → 1，en / en-us / 默认 → 3 */
    private static final Map<String, Integer> LANG_FLAG_ID = Map.of("zh", 2, "zh-cn", 2, "zh-tw", 1, "zh-hk", 1, "en", 3, "en-us", 3);

    private final ObjectMapper mapper = new ObjectMapper();
    private final int parseTimeout;
    private final ViuBrowserSidecar viuBrowserSidecar;
    private final HttpClient directClient;
    private final HttpClient proxyClient;
    /** pageUrl → 缓存的流信息（各清晰度 URL、是否 DRM、是否经代理获取） */
    private final Map<String, CachedStream> cache = new ConcurrentHashMap<>();

    public ViuParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                     @Value("${app.proxy:}") String proxy,
                     ViuBrowserSidecar viuBrowserSidecar) {
        this.parseTimeout = parseTimeout;
        this.viuBrowserSidecar = viuBrowserSidecar;
        this.directClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpClient pc = null;
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI p = URI.create(proxy.trim());
                int port = p.getPort() > 0 ? p.getPort() : 80;
                pc = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), port)))
                        .build();
            } catch (Exception e) {
                log.warn("Viu 代理配置无效，仅使用直连: {}", proxy);
            }
        }
        this.proxyClient = pc;
    }

    @Override
    public Platform platform() { return Platform.VIU; }

    @Override
    public CookiePolicy cookiePolicy() { return CookiePolicy.OPTIONAL; }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String cookieContent = ctx.cookies();
        Groups g = extract(url);
        int areaId = mapAreaId(g.countryCode);
        int langFlagId = mapLangFlagId(g.langCode);

        CookieAuth cookieAuth = resolveAuth(cookieContent);

        // api-gateway-global.viu.com 可直连，且欧美代理出口会被 CloudFront 判为不支持地区（area_id=null）；
        // 优先直连，仅当直连失败或地区受限且配置了代理时回退代理重试。
        HttpClient activeClient = directClient;
        boolean usedProxy = false;
        ProductData product;
        try {
            product = fetchProductData(activeClient, url, g.countryCode, g.videoId, areaId, langFlagId, cookieAuth);
        } catch (Exception directErr) {
            String directMsg = msgOf(directErr);
            if (proxyClient == null) {
                throw (directErr instanceof BusinessException be)
                        ? be : new BusinessException("Viu vod/detail 请求失败：" + directMsg);
            }
            log.info("Viu 直连获取详情失败（{}），尝试代理重试", directMsg);
            activeClient = proxyClient;
            usedProxy = true;
            try {
                product = fetchProductData(activeClient, url, g.countryCode, g.videoId, areaId, langFlagId, cookieAuth);
            } catch (Exception proxyErr) {
                throw new BusinessException("Viu 解析失败：" + directMsg
                        + "；代理重试也失败（" + msgOf(proxyErr) + "）。请确认 .env 中 PROXY_URL 指向的代理已启动，且出口为香港/新加坡");
            }
        }

        // Step 2: 优先使用用户 cookies / 浏览器 sidecar 中的登录 token（并获取会员等级 ut），无则签发匿名 Bearer token
        String token;
        int ut = 1;
        if (cookieAuth.token != null) {
            token = cookieAuth.token;
            ut = cookieAuth.preverifiedLevel != null
                    ? cookieAuth.preverifiedLevel
                    : fetchUserLevel(activeClient, g.countryCode, areaId, langFlagId, token, cookieAuth.cookieHeader);
        } else {
            token = fetchBearerToken(activeClient, g.countryCode, cookieAuth.cookieHeader);
        }

        // Step 3: playback/distribute（需要 Bearer token）
        String vodBase = "https://www.viu.com/ott/" + g.countryCode.toLowerCase();
        StreamData stream = fetchPlaybackDistribute(
                activeClient, product.ccsProductId, g.countryCode, langFlagId, areaId, ut,
                token, cookieAuth.cookieHeader, cookieAuth.token != null, vodBase);

        List<String> subtitles = extractSubtitles(product);
        List<FormatInfo> formats = buildFormats(stream, url, usedProxy, cookieAuth.fingerprint, subtitles);
        if (formats.isEmpty()) {
            if (cookieAuth.token == null) {
                throw new BusinessException("Viu 视频未返回可用清晰度流，该集可能需要 VIP 会员，请在「我的 Cookies」中使用 Viu「登录 VIP」或上传 cookies.txt");
            }
            throw new BusinessException("Viu 视频未返回可用清晰度流，请确认当前登录账号具备 VIP 权限或重新登录");
        }

        long durationSec = stream.durationSec > 0 ? stream.durationSec : 0;

        return new VideoInfo(
                g.videoId,
                product.title,
                product.coverUrl,
                durationSec > 0 ? durationSec : null,
                durationSec > 0 ? YtDlpService.formatDuration(durationSec) : null,
                null,
                Platform.VIU.display,
                null, null,
                formats,
                null,
                subtitles,
                !subtitles.isEmpty(),
                product.description,
                null);
    }

    public String resolveDownloadUrl(String originalUrl, String formatId) {
        return resolveDownloadUrl(originalUrl, formatId, null);
    }

    /** 供 DownloadController 反查已缓存的 m3u8；缺失或登录态变更时重新解析；DRM 加密内容明确拒绝 */
    public String resolveDownloadUrl(String originalUrl, String formatId, String cookieContent) {
        String fp = resolveAuth(cookieContent).fingerprint;
        CachedStream cs = cache.get(originalUrl);
        if (cs == null || !fp.equals(cs.cookieFingerprint)
                || (formatId != null && !cs.urls.containsKey(formatId))) {
            parse(new ParseContext(originalUrl, null, cookieContent));
            cs = cache.get(originalUrl);
        }
        if (cs == null || cs.urls.isEmpty()) {
            return null;
        }
        if (cs.drm) {
            throw new BusinessException("该 Viu 视频受 DRM（Widevine/FairPlay）加密保护，暂不支持下载或在线播放");
        }
        if (formatId != null && cs.urls.containsKey(formatId)) {
            return cs.urls.get(formatId);
        }
        return cs.urls.values().iterator().next();
    }

    public String resolveStreamUrl(String originalUrl, String formatId) {
        return resolveDownloadUrl(originalUrl, formatId, null);
    }

    public String resolveStreamUrl(String originalUrl, String formatId, String cookieContent) {
        return resolveDownloadUrl(originalUrl, formatId, cookieContent);
    }

    public boolean shouldUseProxy(String originalUrl) {
        CachedStream cs = cache.get(originalUrl);
        return cs != null && cs.usedProxy;
    }

    /**
     * 供字幕下载反查：langHint 为前端选中的字幕 URL（命中缓存则直接返回），缺失时返回默认第一条；
     * 缓存缺失或登录态变更时自动重新解析。返回 null 表示该视频无字幕。
     */
    public String resolveSubtitleUrl(String originalUrl, String cookieContent, String langHint) {
        String fp = resolveAuth(cookieContent).fingerprint;
        CachedStream cs = cache.get(originalUrl);
        if (cs == null || !fp.equals(cs.cookieFingerprint)) {
            parse(new ParseContext(originalUrl, null, cookieContent));
            cs = cache.get(originalUrl);
        }
        if (cs == null || cs.subtitles == null || cs.subtitles.isEmpty()) {
            return null;
        }
        if (langHint != null && !langHint.isBlank() && cs.subtitles.contains(langHint)) {
            return langHint;
        }
        return cs.subtitles.get(0);
    }

    /** 取当前生效的 Netscape cookies 文本（用户上传优先，其次浏览器 sidecar 登录态） */
    public String effectiveCookiesTxt(String userCookies) {
        if (userCookies != null && !userCookies.isBlank()) {
            return userCookies;
        }
        ViuBrowserSidecar.ViuSession session = viuBrowserSidecar.session();
        return session != null ? session.cookiesTxt() : null;
    }

    /** 取当前生效的 Cookie 请求头（用户上传优先，其次浏览器 sidecar 登录态） */
    public String effectiveCookieHeader(String userCookies) {
        if (userCookies != null && !userCookies.isBlank()) {
            String h = CookieService.toCookieHeader(userCookies, Platform.VIU);
            if (!h.isBlank()) {
                return h;
            }
        }
        ViuBrowserSidecar.ViuSession session = viuBrowserSidecar.session();
        return session != null ? session.cookieHeader() : null;
    }

    // ===== 内部 =====

    private CookieAuth resolveAuth(String cookieContent) {
        if (cookieContent != null && !cookieContent.isBlank()) {
            String header = CookieService.toCookieHeader(cookieContent, Platform.VIU);
            String token = null;
            String authTokenFallback = null;
            for (CookieService.ParsedCookie c : CookieService.parseNetscape(cookieContent)) {
                if (c.domain() == null || !c.domain().toLowerCase().contains("viu.com")) {
                    continue;
                }
                // 注意：Viu 同时存在 token_param（JSON 设备参数，非鉴权令牌）与 token / authToken（真实 Bearer 令牌），
                // 严禁读取 token_param，否则上游网关会返回 HTTP 401 并误标 cookie 失效。
                if ("token".equalsIgnoreCase(c.name()) || "authToken".equalsIgnoreCase(c.name())) {
                    String v = cleanCookieValue(c.value());
                    if (!v.isBlank()) {
                        if ("token".equalsIgnoreCase(c.name())) {
                            token = v;
                            break;
                        }
                        if (authTokenFallback == null) {
                            authTokenFallback = v;
                        }
                    }
                }
            }
            if (token == null) {
                token = authTokenFallback;
            }
            String fp = "cookie:" + Integer.toHexString((header + "|" + (token != null ? token : "")).hashCode());
            return new CookieAuth(header.isEmpty() ? null : header, token, null, cookieContent, fp);
        }
        ViuBrowserSidecar.ViuSession sidecarSession = viuBrowserSidecar.session();
        if (sidecarSession != null && sidecarSession.token() != null && !sidecarSession.token().isBlank()) {
            String fp = "sidecar:" + Integer.toHexString(sidecarSession.token().hashCode());
            return new CookieAuth(sidecarSession.cookieHeader(), sidecarSession.token(),
                    sidecarSession.userLevel(), sidecarSession.cookiesTxt(), fp);
        }
        return new CookieAuth(null, null, null, null, "anon");
    }

    private static String cleanCookieValue(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.contains("%")) {
            try {
                v = URLDecoder.decode(v, StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {
            }
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private int fetchUserLevel(HttpClient httpClient, String countryCode, int areaId,
                               int langFlagId, String bearerToken, String cookieHeader) {
        String query = String.format(
                "platform_flag_label=web&area_id=%d&language_flag_id=%d"
                        + "&platformFlagLabel=web&areaId=%d&languageFlagId=%d"
                        + "&countryCode=%s&os_flag_id=1",
                areaId, langFlagId, areaId, langFlagId, countryCode.toUpperCase());
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(GATEWAY + "/api/user/info?" + query))
                .timeout(Duration.ofSeconds(parseTimeout))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Origin", "https://www.viu.com")
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .GET();
        if (cookieHeader != null) {
            rb.header("Cookie", cookieHeader);
        }
        try {
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                if (isOutOfRegion(resp.body())) {
                    throw new BusinessException("Viu 当前网络出口地区受限，请切换至香港/新加坡节点（或确认 .env 代理可用）后重试");
                }
                throw new BusinessException("Viu 登录 cookies 已失效，请在「我的 Cookies」中重新登录或上传 cookies.txt");
            }
            if (resp.statusCode() == 200) {
                JsonNode json = mapper.readTree(resp.body());
                int level = json.path("data").path("user").path("user_level").asInt(0);
                if (level > 0) {
                    return level;
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Viu user/info 查询失败，回退默认等级: {}", e.getMessage());
        }
        return 1;
    }

    private Groups extract(String url) {
        Matcher m = URL_RE.matcher(url);
        if (!m.find()) {
            throw new BusinessException("无法识别的 Viu 链接格式");
        }
        String cc = m.group("cc").toUpperCase();
        String ll = m.group("ll").toLowerCase();
        String id = m.group("id");
        return new Groups(cc, ll, id);
    }

    private static int mapAreaId(String cc) {
        Integer v = AREA_ID.get(cc.toUpperCase());
        if (v == null) {
            throw new BusinessException("该 Viu 地区（" + cc + "）暂不支持解析，仅支持 HK/SG/TH/PH");
        }
        return v;
    }

    private static int mapLangFlagId(String lang) {
        return LANG_FLAG_ID.getOrDefault(lang.toLowerCase(), 3);
    }

    private ProductData fetchProductData(HttpClient httpClient, String vodUrl, String countryCode,
                                         String videoId, int areaId, int langFlagId, CookieAuth cookieAuth) {
        String query = String.format(
                "platform_flag_label=web&area_id=%d&language_flag_id=%d&ut=1"
                        + "&platformFlagLabel=web&areaId=%d&languageFlagId=%d"
                        + "&countryCode=%s&r=%%2Fvod%%2Fdetail&product_id=%s&os_flag_id=1",
                areaId, langFlagId, areaId, langFlagId, countryCode.toUpperCase(), videoId);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(GATEWAY + "/api/mobile?" + query))
                .timeout(Duration.ofSeconds(parseTimeout))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.viu.com")
                .header("Referer", vodUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .GET();
        if (cookieAuth.token != null) {
            rb.header("Authorization", "Bearer " + cookieAuth.token);
        }
        if (cookieAuth.cookieHeader != null) {
            rb.header("Cookie", cookieAuth.cookieHeader);
        }
        JsonNode json = send(httpClient, rb.build(), "Viu vod/detail", cookieAuth.token != null);
        if (json.path("status").path("code").asInt(-1) != 0) {
            throw new BusinessException("Viu vod/detail 接口异常：" + json.path("status").path("message").asText(""));
        }
        JsonNode cur = json.path("data").path("current_product");
        if (cur.isMissingNode() || cur.isEmpty()) {
            JsonNode areaNode = json.path("server").path("area");
            if (areaNode.path("area_id").isNull() || areaNode.path("area_id").isMissingNode()) {
                String detected = areaNode.path("country").path("code").asText("未知");
                throw new BusinessException("Viu 当前网络出口地区（" + detected + "）受限，请切换至香港/新加坡节点");
            }
            throw new BusinessException("Viu 未找到对应视频，可能已下架或地区受限");
        }
        String ccsProductId = cur.path("ccs_product_id").asText(null);
        String coverUrl = cur.path("cover_image_url").asText(null);
        String seriesName = json.path("data").path("series").path("name").asText(null);
        String synopsis = cur.path("synopsis").asText("").trim();
        String title;
        if (seriesName != null && !seriesName.isBlank()) {
            title = (!synopsis.isEmpty() && !seriesName.equals(synopsis))
                    ? seriesName + " - " + synopsis
                    : seriesName;
        } else {
            title = synopsis;
        }
        String description = cur.path("description").asText(null);
        return new ProductData(ccsProductId, coverUrl, title, description, json.path("data"));
    }

    private String fetchBearerToken(HttpClient httpClient, String countryCode, String cookieHeader) {
        String uuid = UUID.randomUUID().toString();
        String body = String.format(
                "{\"countryCode\":\"%s\",\"platform\":\"browser\",\"platformFlagLabel\":\"web\","
                        + "\"language\":\"en\",\"uuid\":\"%s\",\"carrierId\":\"0\"}",
                countryCode.toUpperCase(), uuid);
        long rand = (long) (Math.random() * 9_999_999_999L);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(GATEWAY + "/api/auth/token?v=" + rand + "000"))
                .timeout(Duration.ofSeconds(parseTimeout))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookieHeader != null) {
            rb.header("Cookie", cookieHeader);
        }
        JsonNode json = send(httpClient, rb.build(), "Viu auth/token", false);
        String token = json.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new BusinessException("Viu auth/token 未返回有效 token");
        }
        return token;
    }

    private StreamData fetchPlaybackDistribute(HttpClient httpClient, String ccsProductId, String countryCode,
                                               int langFlagId, int areaId, int ut,
                                               String bearerToken, String cookieHeader, boolean hasUserToken,
                                               String vodBase) {
        String query = String.format(
                "platform_flag_label=web&area_id=%d&language_flag_id=%d&ut=%d"
                        + "&platformFlagLabel=web&areaId=%d&languageFlagId=%d"
                        + "&countryCode=%s&ccs_product_id=%s",
                areaId, langFlagId, ut, areaId, langFlagId, countryCode.toUpperCase(), ccsProductId);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(GATEWAY + "/api/playback/distribute?" + query))
                .timeout(Duration.ofSeconds(parseTimeout))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Bearer " + bearerToken)
                .header("Origin", "https://www.viu.com")
                .header("Referer", vodBase + "/")
                .GET();
        if (cookieHeader != null) {
            rb.header("Cookie", cookieHeader);
        }
        JsonNode json = send(httpClient, rb.build(), "Viu playback/distribute", hasUserToken);
        int code = json.path("status").path("code").asInt(-1);
        if (code != 0) {
            String msg = json.path("status").path("message").asText("");
            String lower = msg.toLowerCase();
            if (lower.contains("access violated") || lower.contains("permission")) {
                if (!hasUserToken) {
                    throw new BusinessException("该 Viu 视频为 VIP 专享内容，请在「我的 Cookies」中使用 Viu「登录 VIP」（或上传 cookies.txt）后重试");
                }
                throw new BusinessException("该 Viu 视频需要 VIP 会员权限，请确认当前账号已开通会员或重新登录");
            }
            if (hasUserToken && (lower.contains("token") || lower.contains("auth"))) {
                throw new BusinessException("Viu 登录 cookies 已失效（" + msg + "），请在「我的 Cookies」中重新导出上传 viu.com 的 cookies.txt");
            }
            throw new BusinessException("Viu playback/distribute 接口异常：" + msg);
        }
        JsonNode stream = json.path("data").path("stream");
        if (stream.isMissingNode() || stream.isEmpty()) {
            if (!hasUserToken) {
                throw new BusinessException("Viu 未返回视频流，该内容可能需要 VIP 会员，请在「我的 Cookies」中上传已登录 Viu 的 cookies.txt");
            }
            throw new BusinessException("Viu 未返回视频流，可能是当前账号无该内容权限或视频已下架");
        }
        boolean drm = "y".equalsIgnoreCase(stream.path("is_drm").asText(""));
        // 非 DRM 视频优先使用 stream.url（直达单变体 viu_vod.m3u8，标准 AES-128 可由 yt-dlp 原生下载器与 hls.js 直接消费）
        JsonNode urlMapNode = drm ? stream.path("airplayurl") : stream.path("url");
        if (urlMapNode.isMissingNode() || urlMapNode.isEmpty()) {
            urlMapNode = drm ? stream.path("url") : stream.path("airplayurl");
        }
        JsonNode size = stream.path("size");
        long durationSec = stream.path("duration").asLong(0);
        Map<String, UrlSize> urls = new LinkedHashMap<>();
        urlMapNode.fields().forEachRemaining(e -> {
            String key = e.getKey();
            String url = e.getValue().asText(null);
            if (url != null && !url.isBlank()) {
                long sz = size.path(key).asLong(0);
                urls.put(key, new UrlSize(url, sz));
            }
        });
        return new StreamData(durationSec, drm, urls);
    }

    private List<FormatInfo> buildFormats(StreamData stream, String pageUrl, boolean usedProxy, String cookieFingerprint,
                                          List<String> subtitles) {
        List<Map.Entry<String, UrlSize>> sorted = new ArrayList<>(stream.urls.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, UrlSize> e) -> extractHeight(e.getKey())).reversed());

        List<FormatInfo> formats = new ArrayList<>();
        Map<String, String> cached = new LinkedHashMap<>();
        for (Map.Entry<String, UrlSize> e : sorted) {
            int height = extractHeight(e.getKey());
            String formatId = "viu_" + e.getKey();
            String note = (height > 0 ? height + "p HLS" : "HLS") + (stream.drm ? " (DRM)" : "");
            formats.add(new FormatInfo(formatId, "mp4",
                    height > 0 ? height + "p" : null,
                    height > 0 ? height : null,
                    e.getValue().filesize > 0 ? e.getValue().filesize : null,
                    null, null, null,
                    note,
                    false, false, true));  // serverOnly=true：签名 m3u8 走服务端代理
            cached.put(formatId, e.getValue().url);
        }
        cache.put(pageUrl, new CachedStream(cached, stream.drm, usedProxy, cookieFingerprint, subtitles));
        return formats;
    }

    private static int extractHeight(String key) {
        Matcher m = HEIGHT_RE.matcher(key);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 从 api/mobile data.current_product.subtitle 或同级字段读字幕 URL（SRT） */
    private List<String> extractSubtitles(ProductData product) {
        List<String> out = new ArrayList<>();
        JsonNode subList = product.raw.path("current_product").path("subtitle");
        if (subList.isArray()) {
            for (JsonNode s : subList) {
                String u = s.path("url").asText(null);
                if (u != null && !u.isBlank()) out.add(u);
                String u2 = s.path("second_subtitle_url").asText(null);
                if (u2 != null && !u2.isBlank()) out.add(u2);
            }
        }
        return out;
    }

    private JsonNode send(HttpClient httpClient, HttpRequest req, String what, boolean hasUserToken) {
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                // 403 out_of_region 是出口 IP 地区受限，并非 cookie 失效——必须区分，
                // 否则会把用户有效 cookie 误标为失效（markInvalidIfAuth），导致反复要求重新登录。
                if (isOutOfRegion(resp.body())) {
                    throw new BusinessException("Viu 当前网络出口地区受限，请切换至香港/新加坡节点（或确认 .env 代理可用）后重试");
                }
                if (hasUserToken) {
                    throw new BusinessException("Viu 登录 cookies 已失效，请在「我的 Cookies」中重新导出上传 viu.com 的 cookies.txt");
                }
                throw new BusinessException(what + " 返回 HTTP " + resp.statusCode() + "：" + snippet(resp.body()));
            }
            if (resp.statusCode() != 200) {
                throw new BusinessException(what + " 返回 HTTP " + resp.statusCode() + "：" + snippet(resp.body()));
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(what + " 请求失败：" + e.getMessage());
        }
    }

    /** 判断上游响应是否为地区受限（out_of_region / area_id=null），与 cookie 失效区分开 */
    private static boolean isOutOfRegion(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase();
        return lower.contains("out_of_region") || lower.contains("out of region");
    }

    /** 取异常可读信息，避免 getMessage() 为 null 时对外输出 "null" */
    private static String msgOf(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    private static String snippet(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    // ===== DTO =====

    private record CookieAuth(String cookieHeader, String token, Integer preverifiedLevel,
                              String cookiesTxt, String fingerprint) {}
    private record Groups(String countryCode, String langCode, String videoId) {}
    private record ProductData(String ccsProductId, String coverUrl, String title,
                               String description, JsonNode raw) {}
    private record StreamData(long durationSec, boolean drm, Map<String, UrlSize> urls) {}
    private record UrlSize(String url, long filesize) {}
    private record CachedStream(Map<String, String> urls, boolean drm, boolean usedProxy,
                                String cookieFingerprint, List<String> subtitles) {}
}
