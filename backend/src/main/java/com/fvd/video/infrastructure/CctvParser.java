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
public class CctvParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Pattern GUID_RE = Pattern.compile("var\\s+guid\\s*=\\s*\"([a-f0-9]{32})\"");

    private final ObjectMapper mapper = new ObjectMapper();
    private final int parseTimeout;
    private final HlsClient hlsClient;

    /** 解析缓存：页面 URL → 多清晰度 m3u8 列表（供下载时反查） */
    private final Map<String, List<CctvFormat>> cache = new ConcurrentHashMap<>();
    /** 解析缓存：页面 URL → master m3u8 URL（供 Sidecar 拦截清晰度使用） */
    private final Map<String, String> masterCache = new ConcurrentHashMap<>();

    public CctvParser(@Value("${app.parse-timeout:60}") int parseTimeout, HlsClient hlsClient) {
        this.parseTimeout = parseTimeout;
        this.hlsClient = hlsClient;
    }

    public boolean supports(String url) {
        return Platform.from(url) == Platform.CCTV;
    }

    public VideoInfo parse(String url, String cookies) {
        String cookieHeader = toCookieHeader(cookies);
        String guid = fetchGuid(url, cookieHeader);
        JsonNode info = fetchVdnInfo(guid, cookieHeader);
        String title = info.path("title").asText("央视视频");
        String thumbnail = info.path("image").asText(null);
        // 使用 h5e 端点（播放器实际使用的 CDN，含多清晰度变体）
        String hlsUrl = info.path("manifest").path("hls_h5e_url").asText(null);
        if (hlsUrl == null || hlsUrl.isBlank()) {
            // 回退到旧端点
            hlsUrl = info.path("hls_url").asText(null);
        }
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
            // 同分辨率去重（1200 和 2000 都是 720p，保留高码率的）
            List<HlsClient.HlsVariant> deduped = dedupByHeight(variants);
            for (HlsClient.HlsVariant v : deduped) {
                String formatId = "cctv_" + (v.height() > 0 ? v.height() : ("bw" + v.bandwidth()));
                String label = v.label();
                String resolution = v.height() > 0 ? v.height() + "p" : null;
                formats.add(new FormatInfo(
                        formatId, "mp4", resolution, v.height() > 0 ? v.height() : null,
                        null, null, null, null,
                        label, false, false, true));
                cached.add(new CctvFormat(formatId, v.uri(), v.height(), label));
            }
        }

        cache.put(url, cached);
        masterCache.put(url, hlsUrl);
        long duration = info.path("video").path("totalLength").asLong(0);
        return new VideoInfo(
                guid, title, thumbnail,
                duration > 0 ? (long) duration : null,
                duration > 0 ? YtDlpService.formatDuration((long) duration) : null,
                info.path("play_channel").asText(null),
                Platform.CCTV.display,
                null, null, formats, null, List.of(), false);
    }

    /** 反查已解析的 m3u8 流地址供下载使用 */
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

    /** 反查 master m3u8 URL 和目标清晰度供 Sidecar 解密使用 */
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

    /** record：Sidecar 解密所需的下载参数 */
    public record CctvDownloadParams(String masterUrl, int targetHeight) {}

    /** 提供 cookie 头给 HlsClient 下载时使用 */
    public String getCookieHeader(String netscape) {
        return toCookieHeader(netscape);
    }

    // ===== 内部 =====

    /** 同分辨率只保留高码率（1200 和 2000 都是 720p → 保留 2000） */
    private static List<HlsClient.HlsVariant> dedupByHeight(List<HlsClient.HlsVariant> variants) {
        List<HlsClient.HlsVariant> result = new ArrayList<>();
        for (HlsClient.HlsVariant v : variants) {
            boolean found = false;
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i).height() == v.height() && v.height() > 0) {
                    if (v.bandwidth() > result.get(i).bandwidth()) {
                        result.set(i, v);
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.add(v);
            }
        }
        return result;
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
            if (!m.find()) {
                throw new BusinessException("无法从 CCTV 页面提取视频 GUID");
            }
            return m.group(1);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取 CCTV 视频信息失败：" + e.getMessage());
        }
    }

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

    private record CctvFormat(String formatId, String streamUrl, int height, String label) {
    }

    /** Netscape cookies 文本 → Cookie 请求头（name=value; ...） */
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
}
