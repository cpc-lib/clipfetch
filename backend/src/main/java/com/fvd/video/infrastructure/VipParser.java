package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.application.DownloadService;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.VideoInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vip.61la.com 线路一（bfq.txnp.cn）直取通道：HTTP 拉取播放器页，AES-CBC 解密内嵌密文得流地址。
 * 只接受平台官方 CDN 源（无水印）；解析站只下发第三方源时直接失败，不做第三方兜底、不走浏览器逐线路捕获
 * （其余线路均为第三方解析代理站，不可能给出官方源，浏览器捕获只会拖慢到前端超时）。
 * 支持腾讯视频 v.qq.com、优酷 v.youku.com、爱奇艺 iqiyi.com、芒果TV mgtv.com。
 */
@Slf4j
@Service
public class VipParser {

    public static final String FORMAT_ID = "vip_default";
    private static final List<String> SUPPORTED_HOSTS =
            List.of("v.qq.com", "v.youku.com", "iqiyi.com", "mgtv.com");
    // 线路一播放器页面内嵌 AES-CBC 加密的官方 CDN 直链，可直接 HTTP 解密取流
    private static final String DIRECT_PLAYER_API = "https://bfq.txnp.cn/player?url=";
    private static final Pattern RESULT_PATTERN = Pattern.compile("let result = \"([^\"]+)\"");
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final DownloadService downloadService;

    @Autowired
    public VipParser(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    public boolean supports(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            for (String domain : SUPPORTED_HOSTS) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public VideoInfo parse(String url) {
        DownloadTarget target = resolveDownload(url);
        Long duration = target.duration();
        String quality = target.qn() != null && !target.qn().isBlank() ? " " + target.qn() : "";
        FormatInfo format = new FormatInfo(FORMAT_ID, "mp4", null, null,
                null, null, null, null, "VIP 解析 · " + target.line() + quality, false, false, true);
        String site = siteName(url);
        String title = target.title() != null && !target.title().isBlank()
                ? target.title() : site + " " + videoId(url);
        return new VideoInfo(videoId(url), title, null, duration,
                duration != null ? YtDlpService.formatDuration(duration) : null,
                null, site, null, null, List.of(format), null, List.of(), false);
    }

    public void download(String url, String formatId, String title,
                         HttpServletResponse response, String taskId) {
        if (formatId != null && !FORMAT_ID.equals(formatId)) {
            throw new BusinessException("VIP 解析格式无效，请重新解析视频");
        }
        DownloadTarget target = resolveDownload(url);
        String name = title != null && !title.isBlank() ? title
                : target.title() != null && !target.title().isBlank() ? target.title()
                : "vip-" + videoId(url);
        downloadService.downloadToResponse(target.url(), null,
                name, response, target.cookies(), taskId, target.ytDlpArgs());
    }

    DownloadTarget resolveDownload(String url) {
        if (!supports(url)) {
            throw new BusinessException("不是支持的视频链接（支持腾讯视频/优酷/爱奇艺/芒果TV）");
        }
        log.info("VIP 开始解析: {}", url);
        DownloadTarget direct = resolveDirect(url);
        if (direct == null) {
            throw new BusinessException("解析站未找到该视频的官方源，暂不支持下载");
        }
        return direct;
    }

    /**
     * 线路一（bfq.txnp.cn）播放器页内嵌 let result = "..."，密文+key+iv 拼接：
     * AES-CBC 密文（base64）= result[:-32]，key = result[len-32:len-16]，iv = result[len-16:]。
     * 解密得 JSON，video_info.video.url 为流地址，另含真实标题和清晰度。
     * 只接受平台官方 CDN 源；第三方源返回 null。
     */
    private DownloadTarget resolveDirect(String url) {
        try {
            Map<String, String> headers = Map.of("user-agent", BROWSER_UA,
                    "referer", "https://bfq.txnp.cn/");
            String html = httpGet(DIRECT_PLAYER_API + url, headers);
            Matcher matcher = RESULT_PATTERN.matcher(html);
            if (!matcher.find()) {
                log.info("VIP 直取失败: 播放器页未找到 result 密文（解析站不支持该视频）");
                return null;
            }
            String result = matcher.group(1);
            if (result.length() <= 32) {
                log.info("VIP 直取失败: result 密文长度异常 len={}", result.length());
                return null;
            }
            String key = result.substring(result.length() - 32, result.length() - 16);
            String iv = result.substring(result.length() - 16);
            byte[] encrypted = Base64.getDecoder().decode(result.substring(0, result.length() - 32));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            JsonNode video = OBJECT_MAPPER.readTree(cipher.doFinal(encrypted))
                    .path("video_info").path("video");
            String streamUrl = video.path("url").asText("");
            if (streamUrl.isBlank()) {
                log.info("VIP 直取失败: 解密流地址为空");
                return null;
            }
            if (!isOfficialStream(streamUrl, url)) {
                log.info("VIP 直取失败: 解析站仅下发第三方源 host={}", URI.create(streamUrl).getHost());
                return null;
            }
            Long duration = null;
            try {
                String manifest = httpGet(streamUrl, headers).stripLeading();
                if (manifest.startsWith("#EXTM3U")) {
                    duration = manifestDuration(manifest);
                }
            } catch (Exception e) {
                log.debug("VIP 直取清单读取失败: {}", e.getMessage());
            }
            log.info("VIP 直取命中官方源: {} host={}", video.path("qn").asText(""),
                    URI.create(streamUrl).getHost());
            return new DownloadTarget(streamUrl, headers, null, "线路一", duration,
                    video.path("title").asText(null), video.path("qn").asText(null));
        } catch (Exception e) {
            log.info("VIP 直取失败: {}", e.getMessage());
            return null;
        }
    }

    private static String httpGet(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * 判断流地址是否托管在平台官方 CDN（官方源无水印，第三方中转源常被烧录水印）。
     */
    private static boolean isOfficialStream(String streamUrl, String pageUrl) {
        try {
            String host = URI.create(streamUrl).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            for (String domain : officialDomains(pageUrl)) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static List<String> officialDomains(String pageUrl) {
        try {
            String host = URI.create(pageUrl).getHost();
            if (host == null) {
                return List.of();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.contains("qq.com")) {
                return List.of("qq.com", "gtimg.com");
            }
            if (host.contains("youku.com")) {
                // cibntv.net 是优酷官方 OTT 流媒体 CDN（valipl*.cp31.ott.cibntv.net，ups 播放链）
                return List.of("youku.com", "youkudns.com", "ykimg.com", "cibntv.net");
            }
            if (host.contains("iqiyi.com")) {
                return List.of("iqiyi.com", "qiyi.com", "iq.com", "71.am");
            }
            if (host.contains("mgtv.com")) {
                return List.of("mgtv.com", "hitv.com");
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    static Long manifestDuration(String manifest) {
        if (!manifest.contains("#EXT-X-ENDLIST")) {
            return null;
        }
        double duration = 0;
        for (String line : manifest.split("\\R")) {
            if (line.startsWith("#EXTINF:")) {
                try {
                    duration += Double.parseDouble(line.substring(8).split(",", 2)[0]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return Double.isFinite(duration) && duration > 0 ? Math.round(duration) : null;
    }

    private static String siteName(String url) {
        String host = URI.create(url).getHost();
        if (host == null) {
            return "视频";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.contains("qq.com")) {
            return "腾讯视频";
        }
        if (host.contains("youku.com")) {
            return "优酷";
        }
        if (host.contains("iqiyi.com")) {
            return "爱奇艺";
        }
        if (host.contains("mgtv.com")) {
            return "芒果TV";
        }
        return "视频";
    }

    private static String videoId(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        if (path.endsWith(".html")) {
            return path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
        }
        if (uri.getQuery() != null) {
            for (String part : uri.getQuery().split("&")) {
                if (part.startsWith("vid=")) {
                    return part.substring(4);
                }
            }
        }
        return "video";
    }

    record DownloadTarget(String url, Map<String, String> headers, String cookies, String line, Long duration,
                          String title, String qn) {
        List<String> ytDlpArgs() {
            List<String> args = new ArrayList<>(List.of("-N", "8", "--remux-video", "mp4",
                    "--merge-output-format", "mp4"));
            for (String header : List.of("referer", "user-agent", "origin")) {
                String value = headers.get(header);
                if (value != null && !value.isBlank()) {
                    args.add("--add-header");
                    args.add(header + ":" + value);
                }
            }
            return args;
        }
    }
}
