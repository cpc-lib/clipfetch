package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.infrastructure.service.HlsClient;
import cc.ivera.parser.infrastructure.service.YtDlpService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
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
 * 央视网（tv.cctv.com）视频解析。
 * 流程：页面取 GUID → VDN API 取元数据 + hls_url → HlsClient 解析 master playlist 枚举清晰度。
 * 下载使用 HlsClient（ffmpeg 原生 HLS 下载），不经过 yt-dlp。
 */
@Slf4j
@Service
public class CctvParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern GUID_RE = Pattern.compile("var\\s+guid\\s*=\\s*\"([a-f0-9]{32})\"");
    // .cn 域名部分页面无 var guid，回退提取 itemid1
    private static final Pattern ITEMID_RE = Pattern.compile("var\\s+itemid1\\s*=\\s*\"([A-Za-z0-9]+)\"");

    /**
     * 央视网播放器固定显示的 4 个清晰度按钮，按带宽从高到低映射。
     * 单流视频下 4 个选项都指向同一流，但 label 仍按标准 4 档展示。
     */
    private static final StandardFormat[] STANDARD_FORMATS = {
            new StandardFormat("cctv_1080", "超清 1080p", "1080p", 1080),
            new StandardFormat("cctv_720", "高清 720p", "720p", 720),
            new StandardFormat("cctv_480", "标清 480p", "480p", 480),
            new StandardFormat("cctv_360", "流畅 360p", "360p", 360),
    };
    private final ObjectMapper mapper = new ObjectMapper();
    private final int parseTimeout;
    private final HlsClient hlsClient;
    /**
     * 解析缓存：页面 URL → 多清晰度 m3u8 列表（供下载时反查）
     */
    private final Map<String, List<CctvFormat>> cache = new ConcurrentHashMap<>();
    /**
     * 解析缓存：页面 URL → master m3u8 URL（供 Sidecar 拦截清晰度使用）
     */
    private final Map<String, String> masterCache = new ConcurrentHashMap<>();
    /**
     * 明文 hls_url 变体缓存：页面 URL → 明文流变体（供在线播放取流）
     */
    private final Map<String, List<HlsClient.HlsVariant>> plainCache = new ConcurrentHashMap<>();
    public CctvParser(@Value("${app.parse-timeout:60}") int parseTimeout, HlsClient hlsClient) {
        this.parseTimeout = parseTimeout;
        this.hlsClient = hlsClient;
    }

    @Override
    public Platform platform() {
        return Platform.CCTV;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.OPTIONAL;
    }

    /**
     * Netscape cookies 文本 → Cookie 请求头（name=value; ...）
     */
    static String toCookieHeader(String netscape) {
        if (netscape == null || netscape.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : netscape.split("\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\\t");
            if (cols.length < 7) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(cols[5]).append("=").append(cols[6]);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String cookies = ctx.cookies();
        String cookieHeader = toCookieHeader(cookies);
        String guid = fetchGuid(url, cookieHeader);
        JsonNode info = fetchVdnInfo(guid, cookieHeader);
        String title = info.path("title").asText("央视视频");
        String thumbnail = info.path("image").asText(null);
        // 使用 h5e 端点（播放器实际使用的 CDN，含多清晰度变体）
        String h5eUrl = info.path("manifest").path("hls_h5e_url").asText(null);
        // 明文流端点，供在线播放使用；下载仍优先 h5e
        String plainUrl = info.path("hls_url").asText(null);
        String hlsUrl = h5eUrl == null || h5eUrl.isBlank() ? plainUrl : h5eUrl;
        if (hlsUrl == null || hlsUrl.isBlank()) {
            throw new BusinessException("无法获取 CCTV 视频流地址");
        }

        // 使用 HlsClient 解析 master m3u8 获取实际变体（h5e 端点有多清晰度变体）
        List<HlsClient.HlsVariant> variants = hlsClient.parseManifest(hlsUrl, cookieHeader);

        List<FormatInfo> formats = new ArrayList<>();
        List<CctvFormat> cached = new ArrayList<>();
        if (variants.isEmpty()) {
            // master m3u8 不可用时兜底
            formats.add(new FormatInfo(
                    "cctv_default", "mp4", null, null,
                    null, null, null, null,
                    "默认", false, false, true));
            cached.add(new CctvFormat("cctv_default", hlsUrl, 0, "默认"));
        } else {
            /* 央视网播放器固定显示 4 个清晰度按钮（超清 1080p / 高清 720p / 标清 480p / 流畅 360p），
             * 按带宽从高到低映射到这 4 个标准选项，而非按实际分辨率生成标签。
             * 同分辨率多变体（如 720p@1200 和 720p@2000）不去重——高码率映射到超清、低码率映射到高清。
             * 变体不足 4 个时，缺失档位回退到最高带宽变体（单流视频下 4 个选项都指向同一流）。 */
            List<HlsClient.HlsVariant> sorted = new ArrayList<>(variants);
            sorted.sort((a, b) -> Long.compare(b.bandwidth(), a.bandwidth())); // 高码率在前
            HlsClient.HlsVariant top = sorted.get(0); // 兜底用：变体不足时回退到最高码率
            for (int i = 0; i < 4; i++) {
                HlsClient.HlsVariant v = i < sorted.size() ? sorted.get(i) : top;
                String formatId = STANDARD_FORMATS[i].formatId();
                String label = STANDARD_FORMATS[i].label();
                String resolution = STANDARD_FORMATS[i].resolution();
                int standardHeight = STANDARD_FORMATS[i].height();
                formats.add(new FormatInfo(
                        formatId, "mp4", resolution, standardHeight,
                        null, null, null, null,
                        label, false, false, true));
                // 缓存实际流地址（download 时按 formatId 反查），targetHeight 用实际分辨率供 Sidecar 拦截
                cached.add(new CctvFormat(formatId, v.uri(), v.height(), label));
            }
        }

        cache.put(url, cached);
        masterCache.put(url, hlsUrl);
        rememberPlainVariants(url, plainUrl, hlsUrl, variants, cookieHeader);
        log.info("CCTV 解析完成: url={}, h5e={}, 明文={}, h5e变体={}", url,
                h5eUrl != null && !h5eUrl.isBlank(), plainUrl != null && !plainUrl.isBlank(),
                describeVariants(variants));
        long duration = info.path("video").path("totalLength").asLong(0);
        return new VideoInfo(
                guid, title, thumbnail,
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                info.path("play_channel").asText(null),
                Platform.CCTV.display,
                null, null, formats, null, List.of(), false);
    }

    /**
     * 在线播放取流：返回明文 hls 变体地址；仅提供 h5e 加密流时返回 null。
     */
    public String resolvePlainStreamUrl(String url, String formatId) {
        List<HlsClient.HlsVariant> variants = plainCache.get(url);
        if (variants == null) {
            parse(new ParseContext(url, null, null));
            variants = plainCache.get(url);
        }
        if (variants == null || variants.isEmpty()) {
            log.info("CCTV 明文取流: formatId={}, 无明文变体，转 h5e 解密", formatId);
            return null;
        }
        int targetHeight = standardHeight(formatId);
        HlsClient.HlsVariant best = null;
        HlsClient.HlsVariant chosen = null;
        for (HlsClient.HlsVariant v : variants) {
            if (targetHeight > 0 && v.height() == targetHeight) {
                chosen = v;
                break;
            }
            if (best == null || v.bandwidth() > best.bandwidth()) {
                best = v;
            }
        }
        boolean matched = chosen != null;
        if (!matched) {
            chosen = best;
        }
        log.info("CCTV 明文取流: formatId={}, 目标高度={}, 明文变体={}, 精确匹配={}, 实际高度={}, 地址={}",
                formatId, targetHeight, describeVariants(variants), matched, chosen.height(), chosen.uri());
        // 明文清单可能远低于 h5e：所选档位在明文里没有精确变体，且明文最高变体
        // 低于 h5e 同档位实际分辨率时，改用 h5e 解密，避免用户选高清却播低清
        if (!matched) {
            int encryptedHeight = encryptedHeight(url, formatId);
            if (encryptedHeight > chosen.height()) {
                log.info("CCTV 明文取流: formatId={}, 明文最高 {}p 低于 h5e 档位 {}p，转 h5e 解密",
                        formatId, chosen.height(), encryptedHeight);
                return null;
            }
        }
        return chosen.uri();
    }

    /** h5e 档位对应的实际分辨率；用于判断明文是否值得直接播放 */
    private int encryptedHeight(String url, String formatId) {
        List<CctvFormat> list = cache.get(url);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        for (CctvFormat f : list) {
            if (f.formatId.equals(formatId)) {
                return f.height;
            }
        }
        return list.get(0).height;
    }

    /** 变体摘要（高度@带宽），用于排障日志 */
    private static String describeVariants(List<HlsClient.HlsVariant> variants) {
        return variants.stream()
                .map(v -> v.height() + "p@" + v.bandwidth())
                .toList()
                .toString();
    }

    /**
     * 在线播放取流：返回 h5e 加密变体地址（供后台 Node 解密落盘）；无可用流时返回 null。
     */
    public String resolveEncryptedStreamUrl(String url, String formatId) {
        List<CctvFormat> list = cache.get(url);
        if (list == null) {
            parse(new ParseContext(url, null, null));
            list = cache.get(url);
        }
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (CctvFormat f : list) {
            if (f.formatId.equals(formatId)) {
                return f.streamUrl;
            }
        }
        return list.get(0).streamUrl;
    }

    /**
     * 缓存明文 hls_url 变体供在线播放使用；无明文端点时清空该页面缓存。
     */
    private void rememberPlainVariants(String url, String plainUrl, String hlsUrl,
                                       List<HlsClient.HlsVariant> variants, String cookieHeader) {
        if (plainUrl == null || plainUrl.isBlank()) {
            plainCache.remove(url);
            return;
        }
        if (plainUrl.equals(hlsUrl)) {
            plainCache.put(url, variants);
            log.info("CCTV 明文流与 h5e 同源，变体: {}", describeVariants(variants));
            return;
        }
        try {
            List<HlsClient.HlsVariant> plain = hlsClient.parseManifest(plainUrl, cookieHeader);
            plainCache.put(url, plain);
            log.info("CCTV 明文流变体: {}", describeVariants(plain));
        } catch (Exception e) {
            log.info("CCTV 明文流解析失败: {}", e.getMessage());
            plainCache.remove(url);
        }
    }

    private static int standardHeight(String formatId) {
        if (formatId == null) {
            return 0;
        }
        for (StandardFormat f : STANDARD_FORMATS) {
            if (f.formatId().equals(formatId)) {
                return f.height();
            }
        }
        return 0;
    }

    /**
     * 反查已解析的 m3u8 流地址供下载使用
     */
    public String resolveDownloadUrl(String originalUrl, String formatId) {
        List<CctvFormat> list = cache.get(originalUrl);
        if (list != null) {
            for (CctvFormat f : list) {
                if (f.formatId.equals(formatId)) {
                    return f.streamUrl;
                }
            }
        }
        return null;
    }

    /**
     * 反查 master m3u8 URL 和目标清晰度供 Sidecar 解密使用
     */
    public CctvDownloadParams resolveDownloadParams(String originalUrl, String formatId) {
        String masterUrl = masterCache.get(originalUrl);
        int targetHeight = 0;
        List<CctvFormat> list = cache.get(originalUrl);
        if (list != null) {
            for (CctvFormat f : list) {
                if (f.formatId.equals(formatId)) {
                    targetHeight = f.height;
                    break;
                }
            }
        }
        return new CctvDownloadParams(masterUrl, targetHeight);
    }

    /**
     * 提供 cookie 头给 HlsClient 下载时使用
     */
    public String getCookieHeader(String netscape) {
        return toCookieHeader(netscape);
    }

    private String fetchGuid(String pageUrl, String cookieHeader) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(pageUrl))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*");
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpRequest req = rb.GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("CCTV 页面返回状态码 " + resp.statusCode());
            }
            Matcher m = GUID_RE.matcher(resp.body());
            if (m.find()) return m.group(1);
            // .cn 域名部分页面无 var guid，回退 itemid1
            Matcher m2 = ITEMID_RE.matcher(resp.body());
            if (m2.find()) return m2.group(1);
            throw new BusinessException("无法从 CCTV 页面提取视频 GUID");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取 CCTV 视频信息失败：" + e.getMessage());
        }
    }

    // ===== 内部 =====

    private JsonNode fetchVdnInfo(String guid, String cookieHeader) {
        try {
            String apiUrl = "https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?"
                    + "pid=" + guid + "&tz=-480&im=0&src=0&guid=" + guid
                    + "&vs=0&host=tv.cctv.com";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://tv.cctv.com/");
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                rb.header("Cookie", cookieHeader);
            }
            HttpRequest req = rb.GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("CCTV VDN API 返回状态码 " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("调用 CCTV VDN API 失败：" + e.getMessage());
        }
    }

    /**
     * 标准清晰度选项定义
     */
    private record StandardFormat(String formatId, String label, String resolution, int height) {
    }

    /**
     * record：Sidecar 解密所需的下载参数
     */
    public record CctvDownloadParams(String masterUrl, int targetHeight) {
    }

    private record CctvFormat(String formatId, String streamUrl, int height, String label) {
    }
}
