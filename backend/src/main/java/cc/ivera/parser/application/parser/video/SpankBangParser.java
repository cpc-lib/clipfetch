package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import cc.ivera.parser.infrastructure.service.YtDlpService;
import cc.ivera.parser.infrastructure.sidecar.SpankBangBrowserSidecar;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * SpankBang 适配层。
 *
 * <p>页面受 Cloudflare Bot Management 保护（cf_clearance 绑定真实 Chrome TLS 指纹，
 * yt-dlp 带合法 cookies 仍 403），解析走两条路：
 * <ol>
 *   <li>yt-dlp 直取（站点直连可达的网络环境下有效，如海外服务器）；</li>
 *   <li>失败时回退浏览器 sidecar：真实 Chrome 过 CF 挑战，读取页面 stream_data
 *       各档 CDN 直链（CDN 无 CF 挑战，下载不受影响）。</li>
 * </ol>
 * 下载复用解析时的直链缓存（15 分钟），避免二次触发浏览器解析。
 */
@Slf4j
@Service
public class SpankBangParser extends AbstractVideoParser {

    private static final Pattern VIDEO_PATH = Pattern.compile(
            "^/[0-9a-z]+/(?:video|play|embed)(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final long RESOLVE_CACHE_TTL_MS = 15 * 60_000L;

    private final YtDlpService ytDlp;
    private final SpankBangBrowserSidecar sidecar;

    private final Map<String, CachedResolve> resolveCache = new ConcurrentHashMap<>();

    private record CachedResolve(long cachedAt, List<StreamFormat> formats) { }

    /** sidecar 返回的单档直链 */
    record StreamFormat(String quality, String url, String ext, long filesize) { }

    public record DownloadTarget(String url, List<String> ytDlpArgs) { }

    public SpankBangParser(YtDlpService ytDlp, SpankBangBrowserSidecar sidecar) {
        this.ytDlp = ytDlp;
        this.sidecar = sidecar;
    }

    @Override
    public Platform platform() {
        return Platform.SPANKBANG;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.OPTIONAL;
    }

    @Override
    public boolean supports(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            host = host.toLowerCase();
            return (host.equals("spankbang.com") || host.endsWith(".spankbang.com"))
                    && VIDEO_PATH.matcher(uri.getPath()).find();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        // 快路径：yt-dlp（海外网络直连可达时有效；大陆网络被 CF 拦截走 sidecar）
        try {
            VideoInfo info = ytDlp.parse(url, ctx.cookies());
            if (info.title() != null && !info.title().isBlank() && !info.title().equals(info.id())
                    && info.formats() != null && !info.formats().isEmpty()) {
                log.info("[SpankBang] yt-dlp 解析成功: {} 个清晰度", info.formats().size());
                return info;
            }
            log.info("[SpankBang] yt-dlp 解析不完整，回退浏览器 sidecar");
        } catch (Exception e) {
            log.info("[SpankBang] yt-dlp 解析失败（{}），回退浏览器 sidecar", brief(e));
        }
        return parseViaSidecar(url);
    }

    private VideoInfo parseViaSidecar(String url) {
        JsonNode node = sidecar.resolve(url);
        List<StreamFormat> formats = new ArrayList<>();
        for (JsonNode f : node.path("formats")) {
            formats.add(new StreamFormat(
                    f.path("quality").asText(),
                    f.path("url").asText(),
                    f.path("ext").asText("mp4"),
                    f.path("filesize").asLong(0)));
        }
        if (formats.isEmpty()) {
            throw new IllegalStateException("sidecar 未返回任何格式");
        }
        resolveCache.put(url, new CachedResolve(System.currentTimeMillis(), formats));
        log.info("[SpankBang] sidecar 解析成功: {} 个清晰度, 时长={}",
                formats.size(), formatDuration((long) node.path("duration").asDouble(0)));

        String title = node.path("title").asText("").trim();
        if (title.isBlank()) {
            title = titleFromUrl(url);
        }
        long duration = (long) node.path("duration").asDouble(0);
        return new VideoInfo(videoId(url), title, node.path("thumbnail").asText(null),
                duration, formatDuration(duration), null, Platform.SPANKBANG.display, null, null,
                formats.stream().map(this::toFormatInfo).toList(),
                null, null, false);
    }

    /**
     * 下载地址：复用解析缓存（15 分钟内有效），缓存缺失时重新经 sidecar 解析。
     * 目标格式缺失时回退最高档。
     */
    public DownloadTarget resolveDownload(String url, String formatId) {
        List<StreamFormat> formats = cachedFormats(url);
        if (formats == null) {
            log.info("[SpankBang] 下载缓存缺失，重新经 sidecar 解析: {}", url);
            JsonNode node = sidecar.resolve(url);
            formats = new ArrayList<>();
            for (JsonNode f : node.path("formats")) {
                formats.add(new StreamFormat(
                        f.path("quality").asText(),
                        f.path("url").asText(),
                        f.path("ext").asText("mp4"),
                        f.path("filesize").asLong(0)));
            }
            resolveCache.put(url, new CachedResolve(System.currentTimeMillis(), formats));
        } else {
            log.info("[SpankBang] 使用解析缓存下载地址（{} 个档位）", formats.size());
        }
        StreamFormat target = null;
        for (StreamFormat f : formats) {
            if (f.quality().equalsIgnoreCase(formatId)) {
                target = f;
                break;
            }
        }
        if (target == null) {
            // 缓存里格式名与请求不一致（如 yt-dlp 解析的 id），取最高档兜底
            target = formats.get(0);
            log.info("[SpankBang] 目标格式 {} 不在缓存中，回退最高档 {}", formatId, target.quality());
        }
        List<String> args = target.ext().contains("m3u8")
                ? List.of("-N", "16")   // HLS 分片并发（默认 1）
                : List.of();            // mp4 走默认 aria2c 多连接
        return new DownloadTarget(target.url(), args);
    }

    private List<StreamFormat> cachedFormats(String url) {
        CachedResolve cached = resolveCache.get(url);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt() < RESOLVE_CACHE_TTL_MS) {
            return cached.formats();
        }
        return null;
    }

    private cc.ivera.parser.domain.FormatInfo toFormatInfo(StreamFormat f) {
        Integer height = null;
        var m = Pattern.compile("(\\d+)").matcher(f.quality());
        if (m.find()) {
            height = Integer.parseInt(m.group(1));
        }
        String res = height != null ? height + "p" : f.quality();
        return new cc.ivera.parser.domain.FormatInfo(
                f.quality(), f.ext(), res, height,
                f.filesize() > 0 ? f.filesize() : null, null,
                null, null, res, false, false);
    }

    private static String brief(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    private static String videoId(String url) {
        String[] segments = URI.create(url).getRawPath().split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if ("video".equalsIgnoreCase(segments[i]) && !segments[i + 1].isBlank()) {
                return segments[i + 1];
            }
        }
        return "spankbang";
    }

    private static String formatDuration(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return null;
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private String titleFromUrl(String url) {
        String[] segments = URI.create(url).getRawPath().split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if ("video".equalsIgnoreCase(segments[i]) && !segments[i + 1].isBlank()) {
                String title = URLDecoder.decode(segments[i + 1], StandardCharsets.UTF_8).trim();
                if (!title.isEmpty()) {
                    return Character.toUpperCase(title.charAt(0)) + title.substring(1);
                }
            }
        }
        return null;
    }
}
