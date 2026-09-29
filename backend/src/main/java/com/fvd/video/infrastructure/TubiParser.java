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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tubi（tubitv.com）视频解析。
 *
 * <pre>
 * 页面 URL（/tv-shows/709830/s01-e01-episode-1）
 *     ↓ 提取路径中的数字 content_id
 * 匿名设备认证链（逆向自官方 web 端，无需账号）：
 *     1. POST /device/anonymous/signing_key（PKCE challenge）→ {id, key}
 *     2. POST /device/anonymous/token（TUBI-HMAC-SHA256 请求签名）→ access_token（24h）
 *     ↓
 * GET /api/v3/content（Bearer）→ video_resources[]（hlsv6 master m3u8，带签名 token）
 *     ↓
 * 下载复用 HlsClient：master m3u8 含音频分离组（EXT-X-MEDIA），ffmpeg 自动选最高画质并混流
 * </pre>
 * Tubi CDN 国内直连不通：解析走 HTTP 代理，下载时 ffmpeg 需带 -proxy 参数。
 */
@Slf4j
@Service
public class TubiParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String ACCOUNT_HOST = "https://account.production-public.tubi.io";
    private static final String CONTENT_HOST = "https://content-cdn.production-public.tubi.io";
    /** 页面路径中的数字 content_id（如 /tv-shows/709830/...），要求独立路径段避免误匹配年份 */
    private static final Pattern CONTENT_ID_RE = Pattern.compile("/(\\d{4,})(?:/|$)");
    /** VIDEO_RESOLUTION_720P → 720 */
    private static final Pattern HEIGHT_RE = Pattern.compile("(\\d{3,4})P$");
    private static final DateTimeFormatter TUBI_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper mapper = new ObjectMapper();
    private final int parseTimeout;
    private final String proxy;
    private final HttpClient client;
    /**
     * 解析缓存：页面 URL → formatId → 带签名 token 的 m3u8（token 约 7 天有效，供下载时反查）
     */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public TubiParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                      @Value("${app.proxy:}") String proxy) {
        this.parseTimeout = parseTimeout;
        this.proxy = proxy;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (proxy != null && !proxy.isBlank()) {
            URI p = URI.create(proxy);
            builder.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort())));
        }
        this.client = builder.build();
    }

    public boolean supports(String url) {
        return Platform.from(url) == Platform.TUBI;
    }

    public VideoInfo parse(String url) {
        String contentId = extractContentId(url);
        String deviceId = UUID.randomUUID().toString();
        String accessToken = authenticate(deviceId);
        JsonNode content = fetchContent(contentId, deviceId, accessToken);

        if (content.path("needs_login").asBoolean(false)) {
            throw new BusinessException("该 Tubi 内容需要登录观看，暂不支持解析");
        }
        List<FormatInfo> formats = buildFormats(content, url);
        if (formats.isEmpty()) {
            throw new BusinessException("未获取到 Tubi 视频流，该内容可能不支持网页播放");
        }
        long duration = content.path("duration").asLong(0);
        return new VideoInfo(
                content.path("id").asText(contentId),
                content.path("title").asText("Tubi 视频"),
                firstImage(content),
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                content.path("lang").asText(null),
                Platform.TUBI.display,
                null, null, formats, null, List.of(), false);
    }

    /**
     * 反查已解析的 m3u8 流地址供下载使用；formatId 为空返回最高清晰度
     */
    public String resolveDownloadUrl(String originalUrl, String formatId) {
        Map<String, String> formats = cache.get(originalUrl);
        if (formats == null || formats.isEmpty()) {
            return null;
        }
        if (formatId == null || formatId.isBlank()) {
            return formats.values().iterator().next();
        }
        return formats.get(formatId);
    }

    // ===== 内部 =====

    private static String extractContentId(String url) {
        try {
            Matcher m = CONTENT_ID_RE.matcher(URI.create(url).getPath());
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
        }
        throw new BusinessException("无法从 Tubi 链接中提取视频 ID");
    }

    /**
     * 匿名设备认证链：signing_key（PKCE）→ 请求签名 → access_token
     */
    private String authenticate(String deviceId) {
        try {
            byte[] verifierBytes = new byte[16];
            RANDOM.nextBytes(verifierBytes);
            String verifier = hex(verifierBytes);
            String challenge = Base64.getUrlEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));

            // 1. signing_key：body 字段顺序与官方 web 端一致
            JsonNode sk = postJson(ACCOUNT_HOST + "/device/anonymous/signing_key",
                    "{\"challenge\":\"" + challenge + "\",\"version\":\"1.0.0\","
                            + "\"platform\":\"web\",\"device_id\":\"" + deviceId + "\"}");
            String key = sk.path("key").asText(null);
            String keyId = sk.path("id").asText(null);
            if (key == null || key.isBlank() || keyId == null || keyId.isBlank()) {
                throw new BusinessException("Tubi signing_key 响应异常");
            }

            // 2. token 请求签名（TUBI-HMAC-SHA256）：签名与发送必须是同一字节串
            String body = "{\"verifier\":\"" + verifier + "\",\"id\":\"" + keyId + "\","
                    + "\"platform\":\"web\",\"device_id\":\"" + deviceId + "\"}";
            String date = TUBI_DATE.format(Instant.now());
            String canonical = "POST\n/device/anonymous/token\n\ncontent-type:application/json\n\ncontent-type\n"
                    + sha256Hex(body);
            String hashedCanonical = sha256Hex(canonical);

            byte[] key1 = concat("TUBI".getBytes(StandardCharsets.UTF_8), Base64.getDecoder().decode(key));
            byte[] c1 = hmac(key1, date.substring(0, 8));
            byte[] c2 = hmac(c1, "tubi_request");
            byte[] signature = hmac(c2, "TUBI-HMAC-SHA256\n" + date + "\n" + hashedCanonical);

            JsonNode token = postJson(ACCOUNT_HOST + "/device/anonymous/token"
                    + "?X-Tubi-Algorithm=TUBI-HMAC-SHA256&X-Tubi-Date=" + date
                    + "&X-Tubi-Expires=30&X-Tubi-SignedHeaders=content-type&X-Tubi-Signature=" + hex(signature),
                    body);
            String accessToken = token.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new BusinessException("Tubi 匿名 token 响应异常");
            }
            return accessToken;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Tubi 匿名认证失败：" + e.getMessage());
        }
    }

    /**
     * CMS v3 content API：limit_resolutions 解锁更高清晰度（如 avc_1080p → 720p 档）
     */
    private JsonNode fetchContent(String contentId, String deviceId, String accessToken) {
        String query = "?app_id=tubitv&platform=web&content_id=" + contentId
                + "&device_id=" + deviceId + "&include_channels=true"
                + "&limit_resolutions%5B%5D=avc_1080p"
                + "&video_resources%5B%5D=hlsv6_widevine_nonclearlead"
                + "&video_resources%5B%5D=hlsv6_playready_psshv0"
                + "&video_resources%5B%5D=hlsv6_fairplay"
                + "&video_resources%5B%5D=hlsv6";
        return getJson(CONTENT_HOST + "/api/v3/content" + query, accessToken);
    }

    /**
     * video_resources → 清晰度列表（hlsv6 明文流，按分辨率去重保留首个 CDN，降序），
     * 同时写入缓存供下载反查。
     */
    private List<FormatInfo> buildFormats(JsonNode content, String pageUrl) {
        Map<Integer, String> byHeight = new TreeMap<>(Comparator.reverseOrder());
        for (JsonNode res : content.path("video_resources")) {
            if (!res.path("type").asText("").startsWith("hlsv")) {
                continue; // 仅取明文 HLS 流，跳过 DRM 类型
            }
            String manifestUrl = res.path("manifest").path("url").asText("");
            if (manifestUrl.isBlank()) {
                continue;
            }
            byHeight.putIfAbsent(parseHeight(res.path("resolution").asText("")), manifestUrl);
        }

        List<FormatInfo> formats = new ArrayList<>();
        Map<String, String> cached = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : byHeight.entrySet()) {
            int height = e.getKey();
            String formatId = height > 0 ? "tubi_" + height : "tubi_default";
            formats.add(new FormatInfo(formatId, "mp4", height > 0 ? height + "p" : null,
                    height > 0 ? height : null, null, null, null, null,
                    height > 0 ? height + "p" : "默认", false, false, true));
            cached.put(formatId, e.getValue());
        }
        cache.put(pageUrl, cached);
        return formats;
    }

    private static int parseHeight(String resolution) {
        Matcher m = HEIGHT_RE.matcher(resolution == null ? "" : resolution);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String firstImage(JsonNode content) {
        for (String field : List.of("thumbnails", "posterarts", "hero_images", "landscape_images")) {
            JsonNode arr = content.path(field);
            if (arr.isArray() && arr.size() > 0 && !arr.get(0).asText("").isBlank()) {
                return arr.get(0).asText();
            }
        }
        return null;
    }

    private JsonNode getJson(String url, String bearer) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(parseTimeout))
                .header("User-Agent", UA)
                .header("Accept", "application/json");
        if (bearer != null) {
            rb.header("Authorization", "Bearer " + bearer);
        }
        return send("Tubi 接口", rb);
    }

    private JsonNode postJson(String url, String jsonBody) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(parseTimeout))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json") // 签名头，必须与 canonical 一致且不带 charset
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        return send("Tubi 接口", rb);
    }

    private JsonNode send(String what, HttpRequest.Builder rb) {
        try {
            HttpResponse<String> resp = client.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException(what + "返回状态码 " + resp.statusCode() + "：" + snippet(resp.body()));
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(what + "请求失败：" + e.getMessage());
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String sha256Hex(String data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
