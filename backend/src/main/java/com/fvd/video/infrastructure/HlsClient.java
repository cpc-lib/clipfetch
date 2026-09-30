package com.fvd.video.infrastructure;

import com.fvd.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可复用的 HLS 管道（RFC 8216）：
 *
 * <pre>
 * 网页 URL
 *     ↓
 * Fetch Manifest
 *     ↓
 * 判断类型
 * ┌──────────────────┐
 * │                  │
 * Master Playlist   Media Playlist
 * │                  │
 * ↓                  ↓
 * 解析 #EXT-X-STREAM-INF   解析 Segment 列表
 * │
 * ├── RESOLUTION
 * ├── BANDWIDTH / AVERAGE-BANDWIDTH
 * ├── CODECS
 * ├── FRAME-RATE
 * └── URI（保留 query 参数）
 * │
 * ↓
 * Variant Ranking（height → area → bandwidth → avg-bandwidth → frame-rate）
 * │
 * Manifest 不可信？ → ffprobe 探测实际媒体参数 → 重新排序
 * │
 * ↓
 * 递归 Fetch Variant Playlist → Segment List
 *     ↓
 * ffmpeg 下载分片 → 合并 MP4 → 流式推送 + WebSocket 进度
 * </pre>
 * <p>
 * 关键原则：
 * <ul>
 *   <li>URL 相同 ≠ Stream 相同（动态 manifest、query token 等场景）</li>
 *   <li>不做 URL 去重，保留每个 Variant 对象</li>
 *   <li>不剥离 query 参数</li>
 *   <li>RESOLUTION 优先于 BANDWIDTH 作为画质判据</li>
 *   <li>Manifest 不可信时用 ffprobe 探测真实参数</li>
 * </ul>
 */
@Slf4j
@Component
public class HlsClient {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final String ffmpegPath;
    private final String ffprobePath;
    private final String downloadsDir;
    private final DownloadProgressHandler progress;

    public HlsClient(@Value("${app.ffmpeg-location:}") String ffmpegPath,
                     @Value("${app.downloads-dir:downloads}") String downloadsDir,
                     DownloadProgressHandler progress) {
        String resolved = resolveExec(ffmpegPath);
        this.ffmpegPath = resolved;
        // ffprobe 与 ffmpeg 同目录
        Path binDir = Path.of(resolved).getParent();
        String probeName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffprobe.exe" : "ffprobe";
        this.ffprobePath = binDir != null ? binDir.resolve(probeName).toString() : "ffprobe";
        this.downloadsDir = downloadsDir;
        this.progress = progress;
    }

    private static String resolveExec(String path) {
        if (path == null || path.isBlank()) return "ffmpeg";
        Path p = Path.of(path);
        if (Files.isDirectory(p)) {
            String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";
            return p.resolve(exe).toString();
        }
        return path;
    }

    // ════════════════ 数据模型 ════════════════

    /**
     * 判断是否为 Master Playlist（RFC 8216 §4.3.4）。
     * Master Playlist 包含 #EXT-X-STREAM-INF 或 #EXT-X-MEDIA。
     */
    public static boolean isMasterPlaylist(String content) {
        return content.contains("#EXT-X-STREAM-INF") || content.contains("#EXT-X-MEDIA:");
    }

    /**
     * 按画质降序排列。
     * 优先级：height → width×height → BANDWIDTH → AVERAGE-BANDWIDTH → FRAME-RATE
     */
    public static List<HlsVariant> rankVariants(List<HlsVariant> variants) {
        if (variants.size() <= 1) return variants;
        List<HlsVariant> sorted = new ArrayList<>(variants);
        sorted.sort(Comparator
                .comparingInt(HlsVariant::height)
                .thenComparingInt(v -> v.width() * v.height())
                .thenComparingLong(HlsVariant::bandwidth)
                .thenComparingLong(HlsVariant::averageBandwidth)
                .thenComparingDouble(HlsVariant::frameRate)
                .reversed()
        );
        return sorted;
    }

    // ════════════════ Manifest 解析 ════════════════

    /**
     * 判断是否需要 ffprobe 探测：
     * 所有 variant 共享同一 URI 且 RESOLUTION 缺失。
     */
    private static boolean shouldProbe(List<HlsVariant> variants) {
        if (variants.isEmpty()) return false;
        boolean allSameUrl = variants.stream().map(HlsVariant::uri).distinct().count() == 1;
        boolean anyMissingResolution = variants.stream().anyMatch(v -> v.height() <= 0);
        return allSameUrl && anyMissingResolution;
    }

    private static long extractSize(String line) {
        // ffmpeg 8.x: size=256KiB（周期行）/ Lsize=78KiB（汇总行）；旧版: size=256kB
        Matcher m = Pattern.compile("[Ll]?size=\\s*(\\d+(?:\\.\\d+)?)\\s*(KiB|MiB|GiB|kB|MB|GB)?")
                .matcher(line);
        if (!m.find()) return -1;
        double value = Double.parseDouble(m.group(1));
        long factor = 1024L;
        String unit = m.group(2);
        if (unit != null) {
            factor = switch (unit) {
                case "MiB", "MB" -> 1024L * 1024;
                case "GiB", "GB" -> 1024L * 1024 * 1024;
                default -> 1024L;
            };
        }
        return (long) (value * factor);
    }

    /**
     * 解析相对 URI 为绝对 URI。
     * 不剥离 query 参数（RFC 8216 §4.3.4.2：URI 可包含 query token）。
     */
    static String resolveRelative(String base, String relative) {
        if (relative.startsWith("/")) {
            // 绝对路径：取 base 的 scheme://host
            int idx = base.indexOf("://");
            if (idx > 0) {
                int slash = base.indexOf('/', idx + 3);
                String host = slash > 0 ? base.substring(0, slash) : base;
                return host + relative;
            }
        }
        // 相对路径：替换 base 最后一段
        int lastSlash = base.lastIndexOf('/');
        return base.substring(0, lastSlash + 1) + relative;
    }

    private static long extractAttrLong(String line, String attr) {
        Matcher m = Pattern.compile(attr + "=(\\d+)").matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    // ════════════════ Variant 排序 ════════════════

    private static double extractAttrDouble(String line, String attr) {
        Matcher m = Pattern.compile(attr + "=([\\d.]+)").matcher(line);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    // ════════════════ ffprobe 探测 ════════════════

    private static String extractAttrStr(String line, String attr) {
        Matcher m = Pattern.compile(attr + "=\"([^\"]+)\"").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static int[] extractResolution(String line) {
        Matcher m = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(line);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return null;
    }

    private static double parseExtinfDuration(String line) {
        // #EXTINF:6.0, title
        Matcher m = Pattern.compile("#EXTINF:([\\d.]+)").matcher(line);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    // ════════════════ 下载 ════════════════

    private static String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) return "video";
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    // ════════════════ 兼容旧 API ════════════════

    /**
     * 获取 manifest 内容，判断类型并解析。
     * Master Playlist → 返回所有 Variant（按画质降序）。
     * Media Playlist  → 返回单个 Variant（URI 即自身）。
     */
    public List<HlsVariant> parseManifest(String manifestUrl, String cookieHeader) {
        return parseManifest(manifestUrl, cookieHeader, null);
    }

    /**
     * @param proxyOverride 非空时本次 manifest 抓取走指定代理（由调用方按平台/地区决定），为空直连
     */
    public List<HlsVariant> parseManifest(String manifestUrl, String cookieHeader, String proxyOverride) {
        try {
            String body = fetchText(manifestUrl, cookieHeader, proxyOverride);
            if (isMasterPlaylist(body)) {
                List<HlsVariant> variants = parseStreamInfVariants(body, manifestUrl);
                // 如果 manifest 不可信（所有 variant 同 URL 且无 RESOLUTION），用 ffprobe 探测
                if (shouldProbe(variants)) {
                    log.info("Manifest 元数据不可信，启动 ffprobe 探测");
                    List<HlsVariant> probed = probeVariants(variants, cookieHeader);
                    if (!probed.isEmpty()) {
                        return rankVariants(probed);
                    }
                }
                return rankVariants(variants);
            } else {
                // Media Playlist：本身就是媒体流
                return List.of(new HlsVariant(manifestUrl, 0, 0, 0, 0, null, 0));
            }
        } catch (Exception e) {
            log.warn("解析 manifest 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 Master Playlist 中的所有 Variant。
     * 不做 URL 去重，保留 query 参数。
     */
    private List<HlsVariant> parseStreamInfVariants(String body, String masterUrl) {
        List<HlsVariant> result = new ArrayList<>();
        String[] lines = body.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                long bandwidth = extractAttrLong(line, "BANDWIDTH");
                long avgBandwidth = extractAttrLong(line, "AVERAGE-BANDWIDTH");
                int[] resolution = extractResolution(line);
                int width = resolution != null ? resolution[0] : 0;
                int height = resolution != null ? resolution[1] : 0;
                String codecs = extractAttrStr(line, "CODECS");
                double frameRate = extractAttrDouble(line, "FRAME-RATE");
                // 下一行是 Variant URI（保留 query 参数）
                if (i + 1 < lines.length) {
                    String variantUri = lines[i + 1].trim();
                    if (!variantUri.startsWith("http")) {
                        variantUri = resolveRelative(masterUrl, variantUri);
                    }
                    result.add(new HlsVariant(variantUri, width, height,
                            bandwidth, avgBandwidth, codecs, frameRate));
                }
            }
        }
        return result;
    }

    /**
     * 解析 Master Playlist 中的字幕轨道（#EXT-X-MEDIA:TYPE=SUBTITLES）。
     * 返回每个字幕轨道的绝对 URI、语言代码和显示名称。
     */
    public List<SubtitleTrack> parseSubtitleTracks(String manifestUrl, String cookieHeader) {
        try {
            String body = fetchText(manifestUrl, cookieHeader);
            List<SubtitleTrack> tracks = new ArrayList<>();
            for (String line : body.split("\\n")) {
                String l = line.trim();
                if (!l.startsWith("#EXT-X-MEDIA:") || !l.contains("TYPE=SUBTITLES")) {
                    continue;
                }
                String uri = extractAttrStr(l, "URI");
                if (uri == null || uri.isBlank()) {
                    continue;
                }
                if (!uri.startsWith("http")) {
                    uri = resolveRelative(manifestUrl, uri);
                }
                String language = extractAttrStr(l, "LANGUAGE");
                String name = extractAttrStr(l, "NAME");
                tracks.add(new SubtitleTrack(uri,
                        language != null ? language : "und",
                        name != null ? name : language));
            }
            return tracks;
        } catch (Exception e) {
            log.warn("解析字幕轨道失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ════════════════ 内部方法 ════════════════

    /**
     * 解析 Media Playlist，返回分片列表。
     */
    public List<HlsSegment> parseMediaPlaylist(String playlistUrl, String cookieHeader) {
        try {
            String body = fetchText(playlistUrl, cookieHeader);
            List<HlsSegment> segments = new ArrayList<>();
            String[] lines = body.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("#EXTINF:")) {
                    double duration = parseExtinfDuration(line);
                    if (i + 1 < lines.length) {
                        String segUri = lines[i + 1].trim();
                        if (!segUri.startsWith("http")) {
                            segUri = resolveRelative(playlistUrl, segUri);
                        }
                        segments.add(new HlsSegment(segUri, duration));
                    }
                }
            }
            return segments;
        } catch (Exception e) {
            log.warn("解析 media playlist 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 用 ffprobe 探测每个 variant 的第一个分片，获取真实 width/height/codec/bitrate。
     */
    private List<HlsVariant> probeVariants(List<HlsVariant> variants, String cookieHeader) {
        List<HlsVariant> probed = new ArrayList<>();
        for (HlsVariant v : variants) {
            try {
                // 取 media playlist 的第一个分片
                List<HlsSegment> segments = parseMediaPlaylist(v.uri(), cookieHeader);
                if (segments.isEmpty()) {
                    probed.add(v);
                    continue;
                }
                String segUrl = segments.get(0).url();
                int[] dims = probeMediaDimensions(segUrl);
                if (dims != null) {
                    probed.add(new HlsVariant(v.uri(), dims[0], dims[1],
                            v.bandwidth(), v.averageBandwidth(), v.codecs(), v.frameRate()));
                } else {
                    probed.add(v);
                }
            } catch (Exception e) {
                log.debug("ffprobe 探测失败: {}", e.getMessage());
                probed.add(v);
            }
        }
        return probed;
    }

    /**
     * ffprobe 探测媒体分片的真实分辨率。
     * 返回 [width, height]，失败返回 null。
     */
    private int[] probeMediaDimensions(String segmentUrl) {
        try {
            List<String> cmd = List.of(
                    ffprobePath, "-v", "quiet",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=p=0",
                    segmentUrl
            );
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            // 输出格式: 1920,1080
            if (output.matches("\\d+,\\d+")) {
                String[] parts = output.split(",");
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            }
        } catch (Exception e) {
            log.debug("ffprobe 探测失败 {}: {}", segmentUrl, e.getMessage());
        }
        return null;
    }

    /**
     * 用 ffmpeg 下载 m3u8 → 合并 MP4 → 流式推送给浏览器，带 WebSocket 进度。
     */
    public void downloadToResponse(String m3u8Url, String title, String cookieHeader,
                                   HttpServletResponse response, String taskId) {
        Path dir = null;
        try {
            dir = Files.createDirectories(Path.of(downloadsDir, UUID.randomUUID().toString()));
            Path outputFile = dir.resolve("video.mp4");

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(m3u8Url);
            cmd.add("-c");
            cmd.add("copy");
            cmd.add("-bsf:a");
            cmd.add("aac_adtstoasc");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add(outputFile.toString());

            log.info("HLS 下载: {} -> {}", m3u8Url, outputFile);

            // HLS 无 Content-Length：用分片总时长配合 ffmpeg time= 推算进度百分比
            long durationSec = Math.round(parseMediaPlaylist(m3u8Url, cookieHeader)
                    .stream().mapToDouble(HlsSegment::duration).sum());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Thread readerThread = readFfmpegProgress(process.getInputStream(), taskId, durationSec);

            int code = process.waitFor();
            readerThread.join(3000);

            if (code != 0 || !Files.exists(outputFile) || Files.size(outputFile) == 0) {
                progress.sendError(taskId, "HLS 下载失败，请稍后重试");
                throw new BusinessException("HLS 下载失败（ffmpeg 退出码 " + code + "）");
            }

            long size = Files.size(outputFile);
            String filename = sanitizeTitle(title) + ".mp4";
            response.setContentType("video/mp4");
            response.setContentLengthLong(size);
            response.setHeader("Content-Disposition",
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
            response.setHeader("Cache-Control", "no-store");

            try (OutputStream out = response.getOutputStream()) {
                Files.copy(outputFile, out);
                out.flush();
            }
            progress.sendDone(taskId);
            log.info("HLS 下载完成: {} ({})", filename, YtDlpService.humanSize(size));
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("下载被中断");
        } catch (Exception e) {
            throw new BusinessException("HLS 下载失败：" + e.getMessage());
        } finally {
            cleanup(dir);
        }
    }

    /**
     * 旧版接口：解析 master playlist 返回旧 Variant 列表。
     * 内部调用新 API，转换结果。
     */
    public List<Variant> parseMasterPlaylist(String masterUrl, String cookieHeader) {
        List<HlsVariant> variants = parseManifest(masterUrl, cookieHeader);
        List<Variant> result = new ArrayList<>();
        for (HlsVariant v : variants) {
            result.add(new Variant((int) v.bandwidth(), v.width(), v.height(), v.uri()));
        }
        return result;
    }

    private Thread readFfmpegProgress(java.io.InputStream is, String taskId, long durationSec) {
        Thread t = new Thread(() -> {
            long lastSentTs = 0;
            long lastSize = 0;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (taskId == null) continue;
                    if (!line.contains("size=") || !line.contains("time=")) continue;
                    long currentSize = extractSize(line);
                    if (currentSize <= 0) continue;
                    long now = System.currentTimeMillis();
                    if (now - lastSentTs <= 300) continue;
                    double speed = lastSize > 0
                            ? (currentSize - lastSize) * 1000.0 / Math.max(1, now - lastSentTs) : 0;
                    // 总量未知：有总时长时按 ffmpeg time= 时间轴比例反推总字节数，驱动前端百分比
                    long total = -1;
                    if (durationSec > 0) {
                        double elapsed = extractTimeSeconds(line);
                        if (elapsed > 0) {
                            double pct = Math.min(elapsed / durationSec, 0.99);
                            if (pct > 0) {
                                total = Math.round(currentSize / pct);
                            }
                        }
                    }
                    progress.sendProgress(taskId, currentSize, total, Math.max(0, Math.round(speed)));
                    lastSentTs = now;
                    lastSize = currentSize;
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 解析 ffmpeg stats 的 time=HH:MM:SS.xx 为秒 */
    private static double extractTimeSeconds(String line) {
        Matcher m = Pattern.compile("time=(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)").matcher(line);
        if (!m.find()) return -1;
        return Integer.parseInt(m.group(1)) * 3600
                + Integer.parseInt(m.group(2)) * 60
                + Double.parseDouble(m.group(3));
    }

    private String fetchText(String url, String cookieHeader) throws Exception {
        return fetchText(url, cookieHeader, null);
    }

    private String fetchText(String url, String cookieHeader, String proxyOverride) throws Exception {
        HttpClient client = buildHttpClient(proxyOverride);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA);
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            rb.header("Cookie", cookieHeader);
        }
        HttpResponse<String> resp = client.send(rb.GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new BusinessException("获取 m3u8 失败: HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 构建 HttpClient；proxyOverride 非空时走该代理（http 代理；调用方负责地区判断），否则直连 */
    private HttpClient buildHttpClient(String proxyOverride) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyOverride != null && !proxyOverride.isBlank()) {
            URI proxyUri = URI.create(proxyOverride);
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort())));
        }
        return builder.build();
    }

    private void cleanup(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var list = Files.list(dir)) {
            list.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir);
        }
    }

    /**
     * HLS Variant（RFC 8216 §4.3.4.2）。
     * 同一 URI 可能对应不同 stream（动态 manifest），不能按 URL 去重。
     */
    public record HlsVariant(
            String uri,
            int width,
            int height,
            long bandwidth,
            long averageBandwidth,
            String codecs,
            double frameRate
    ) {
        public String label() {
            if (height <= 0) return "默认";
            if (height <= 480) return "标清 " + height + "p";
            if (height <= 720) return "高清 " + height + "p";
            return "超清 " + height + "p";
        }

        /**
         * 是否有可靠的 manifest 元数据（RESOLUTION + BANDWIDTH 都存在）
         */
        public boolean hasReliableMetadata() {
            return height > 0 && bandwidth > 0;
        }
    }

    /**
     * Media Playlist 中的分片
     */
    public record HlsSegment(String url, double duration) {
    }

    /**
     * Master Playlist 中的字幕轨道（#EXT-X-MEDIA:TYPE=SUBTITLES）。
     */
    public record SubtitleTrack(String uri, String language, String name) {
    }

    /**
     * 旧版 Variant（向后兼容 CctvParser）
     */
    public record Variant(int bandwidth, int width, int height, String url) {
        public String label() {
            if (height <= 0) return "默认";
            if (height <= 480) return "标清 " + height + "p";
            if (height <= 720) return "高清 " + height + "p";
            return "超清 " + height + "p";
        }
    }
}
