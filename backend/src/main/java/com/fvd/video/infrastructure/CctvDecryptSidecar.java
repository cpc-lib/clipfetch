package com.fvd.video.infrastructure;

import com.fvd.shared.web.BusinessException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.FunctionCallback;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CCTV h5e 流 WASM 解密 sidecar。
 *
 * <p>h5e 端点的视频 ES 被 {@code cctv.worker.js} 中的 WebAssembly 加密，
 * 服务端无法直接解密；本组件启动无头 Chromium 加载 CCTV 视频页，
 * 通过拦截 {@link com.microsoft.playwright.Page#exposeFunction} 与
 * {@code SourceBuffer.prototype.appendBuffer} 抓取浏览器解密后的段数据，
 * 再用 ffmpeg 合并成 MP4 推送给客户端。
 *
 * <p>清晰度通过 {@link Route} 拦截 master m3u8 重写只保留指定 height 的变体实现。
 */
@Slf4j
@Component
public class CctvDecryptSidecar {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";

    private static final Pattern RESOLUTION_RE = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");

    private final String downloadsDir;
    private final String ffmpegPath;
    private final String chromiumPath;
    private final DownloadProgressHandler progress;
    /** 浏览器解密段最长等待时间（毫秒）——段获取速率约 0.6x，60 分钟视频需约 100 分钟，默认 120 分钟 */
    private final long decryptTimeoutMs;

    public CctvDecryptSidecar(@Value("${app.ffmpeg-location:}") String ffmpegLocation,
                             @Value("${app.downloads-dir:downloads}") String downloadsDir,
                             @Value("${app.chromium-path:}") String chromiumPath,
                             @Value("${app.cctv-decrypt-timeout-ms:600000}") long decryptTimeoutMs,
                             DownloadProgressHandler progress) {
        this.downloadsDir = downloadsDir;
        this.ffmpegPath = resolveFfmpeg(ffmpegLocation);
        this.chromiumPath = chromiumPath;
        this.progress = progress;
        this.decryptTimeoutMs = decryptTimeoutMs;
    }

    /**
     * 解密并下载。
     *
     * @param pageUrl      CCTV 视频页 URL（用户提交的原 URL）
     * @param targetHeight 目标清晰度高度（720/360/270），0 表示让播放器自选
     * @param masterUrl    已知的 master m3u8 URL，用于 Route 精确匹配；null 则跳过清晰度拦截
     * @param title        文件名
     * @param cookieHeader Cookie 请求头（可空）
     * @param response     Servlet 响应
     * @param taskId       WebSocket 进度任务 ID
     */
    public void decryptAndDownload(String pageUrl, int targetHeight, String masterUrl,
                                   String title, String cookieHeader,
                                   HttpServletResponse response, String taskId) {
        Path dir = null;
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of(
                            "--disable-blink-features=AutomationControlled",
                            "--no-sandbox",
                            "--disable-dev-shm-usage",
                            "--autoplay-policy=no-user-gesture-required",
                            "--mute-audio"
                    ));
            // 优先用配置的浏览器路径，其次尝试系统 Chrome（Windows 常见位置）
            String resolvedChromium = resolveChromium(chromiumPath);
            if (resolvedChromium != null) {
                opts.setExecutablePath(Path.of(resolvedChromium));
                log.info("使用浏览器: {}", resolvedChromium);
            }
            try {
                Browser browser = pw.chromium().launch(opts);
                BrowserContext ctx = browser.newContext(
                        new Browser.NewContextOptions().setUserAgent(UA));
                Page page = ctx.newPage();

                // 调试：捕获浏览器 console 日志
                page.onConsoleMessage(message -> {
                    String text = message.text();
                    if (text != null && !text.isBlank()) {
                        log.info("[browser:{}] {}", message.type(), text.length() > 300 ? text.substring(0, 300) + "..." : text);
                    }
                });

                // 调试：监控 .ts 段请求，判断 player 是否在加载后续段
                page.onRequest(request -> {
                    String url = request.url();
                    if (url.contains(".ts") || url.contains(".m3u8") || url.contains("hls/")) {
                        log.info("[net:req] {} {} {}", request.method(), url.length() > 150 ? url.substring(0, 150) + "..." : url, request.resourceType());
                    }
                });
                page.onResponse(resp -> {
                    String url = resp.url();
                    if (url.contains(".ts")) {
                        log.info("[net:resp] {} status={} size={}",
                                url.length() > 120 ? "..." + url.substring(url.length() - 100) : url,
                                resp.status(), resp.headers().get("content-length"));
                    }
                });

                dir = Files.createDirectories(Path.of(downloadsDir, UUID.randomUUID().toString()));
                final Path segDir = dir;
                AtomicInteger segCounter = new AtomicInteger(0);
                AtomicLong totalBytes = new AtomicLong(0);

                // Java 侧接收段数据：JS 调 window.__captureSegment(base64)
                page.exposeFunction("__captureSegment", (FunctionCallback) args -> {
                    try {
                        byte[] data = Base64.getDecoder().decode((String) args[0]);
                        int idx = segCounter.incrementAndGet();
                        Files.write(segDir.resolve(String.format("seg-%05d.bin", idx)), data);
                        long total = totalBytes.addAndGet(data.length);
                        progress.sendProgress(taskId, total, -1, 0);
                    } catch (Exception e) {
                        log.warn("段写入失败: {}", e.getMessage());
                    }
                    return null;
                });

                // 页面加载前注入：拦截 SourceBuffer.appendBuffer + 去除 webdriver 标记
                page.addInitScript(INIT_SCRIPT);

                // 清晰度拦截：内容 master m3u8 只保留目标高度变体（用 glob 匹配，因为播放器实际请求的 host
                // 是 dh5wx.cntv.kfcbest.com 而非我们缓存的 dh5ws01.v.cntv.cn）
                if (targetHeight > 0) {
                    page.route("**/h5e/hls/main/**/main.m3u8**", route -> {
                        try {
                            String body = route.fetch().text();
                            String rewritten = filterMasterPlaylist(body, targetHeight);
                            route.fulfill(new Route.FulfillOptions()
                                    .setContentType("application/vnd.apple.mpegurl")
                                    .setBody(rewritten));
                        } catch (Exception e) {
                            log.debug("master m3u8 拦截失败，回退原始: {}", e.getMessage());
                            route.resume();
                        }
                    });
                    // 实验性：不拦截广告，让广告自然播放，看 player 能否自然过渡到内容。
                    // 之前的 abort/fulfill 都会让 player 在 ad 后重新初始化 worker 但不继续加载内容。
                    // 这里观察：自然播放 ad 后 player 是否会切换到内容。
                    // 同时拦截 VAST/ad 请求的 tracking（p.data.cctv.com play.1.41 等）以减少干扰。
                    // 保留无操作 route 仅用于日志
                    page.route("**/adv/hls/**", route -> {
                        String url = route.request().url();
                        log.info("[ad-natural] {}", url.length() > 120 ? "..." + url.substring(url.length() - 100) : url);
                        route.resume();
                    });
                }

                log.info("CCTV 解密：加载视频页 {}", pageUrl);
                page.navigate(pageUrl);
                page.waitForLoadState();

                // 等待 video 元素出现并触发播放
                page.waitForSelector("video", new Page.WaitForSelectorOptions().setTimeout(30000));
                page.evaluate(START_PLAY_SCRIPT);

                // 输出诊断信息：video 元素状态 + MSE / SourceBuffer 是否被使用
                Object diag = page.evaluate(DIAG_SCRIPT);
                log.info("CCTV 诊断: {}", diag);

                // 等待视频结束：段流停止 30s 或视频自然结束（必须已捕获足够段，避免 player 重载时误判）
                // 注意：CCTV 播放器会在初始化阶段触发"new load request"导致 v.ended 短暂为 true，
                // 因此必须额外要求 segCount > 5 且无新增段才认定完成。
                // 注意：必须用多行文本块，单行字符串拼接会让 // 注释吞掉后续代码导致 SyntaxError。
                page.waitForFunction(WAIT_DONE_SCRIPT, null,
                        new Page.WaitForFunctionOptions().setTimeout(decryptTimeoutMs));

                int segCount = segCounter.get();
                log.info("CCTV 解密完成：捕获 {} 段，共 {} 字节", segCount, totalBytes.get());
                browser.close();

                if (segCount == 0) {
                    throw new BusinessException("CCTV 解密未捕获到任何段数据");
                }

                // 合并 → MP4
                Path outputFile = dir.resolve("video.mp4");
                mergeSegments(dir, segCount, outputFile);

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
                log.info("CCTV 解密下载完成: {} ({})", filename, YtDlpService.humanSize(size));
            } catch (com.microsoft.playwright.PlaywrightException e) {
                throw new BusinessException("浏览器解密失败：" + e.getMessage());
            }
        } catch (BusinessException e) {
            progress.sendError(taskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            progress.sendError(taskId, "CCTV 解密下载失败：" + e.getMessage());
            throw new BusinessException("CCTV 解密下载失败：" + e.getMessage());
        } finally {
            cleanup(dir);
        }
    }

    /**
     * 重写 master playlist：只保留目标高度的变体；若没有完全匹配则保留最接近的。
     * 同高度多个变体时，保留带宽最高的（CCTV 1200/2000 都是 720p，应取 2000）。
     */
    static String filterMasterPlaylist(String body, int targetHeight) {
        String[] lines = body.split("\\n");
        StringBuilder out = new StringBuilder();
        boolean keepNext = false;
        int bestDiff = Integer.MAX_VALUE;
        int bestBw = -1;
        int bestIdx = -1;
        // 先扫一遍找最接近的变体；同高度取带宽更高
        for (int i = 0; i < lines.length; i++) {
            Matcher m = RESOLUTION_RE.matcher(lines[i]);
            if (m.find()) {
                int h = Integer.parseInt(m.group(2));
                int diff = Math.abs(h - targetHeight);
                int bw = parseBandwidth(lines[i]);
                if (diff < bestDiff || (diff == bestDiff && bw > bestBw)) {
                    bestDiff = diff;
                    bestBw = bw;
                    bestIdx = i;
                }
            }
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                keepNext = (i == bestIdx);
                if (keepNext) {
                    out.append(line).append('\n');
                }
            } else if (!line.startsWith("#")) {
                if (keepNext) {
                    out.append(line).append('\n');
                }
                keepNext = false;
            } else {
                if (keepNext) {
                    out.append(line).append('\n');
                }
            }
        }
        String result = out.toString();
        return result.isBlank() ? body : result;
    }

    private static int parseBandwidth(String streamInfLine) {
        Matcher m = Pattern.compile("BANDWIDTH=(\\d+)").matcher(streamInfLine);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * 合并所有段为最终 MP4。
     *
     * <p>由于 wrapAppend 按 SourceBuffer 调用顺序交替写盘：video_init(1) → audio_init(2) →
     * video_seg0(3) → audio_seg0(4) → ...，因此按奇偶索引分离 video/audio 两个轨道：
     * 奇数索引=video，偶数索引=audio。
     *
     * <p>合并策略（验证可行）：二进制拼接 init 段（含 ftyp+moov+moof0+mdat0）+ 后续 moof 段，
     * 再用 ffmpeg 重新封装。不能用 ffmpeg concat demuxer：moof 段没有 moov，demuxer 无法独立打开。
     *
     * <p>track_id 对齐：worker.js 生成的 init 段 trex track_id=1，但 moof 段 tfhd track_id=256，
     * 不一致会导致 ffmpeg 报 "could not find corresponding trex"。合并前需把 init 的 trex track_id
     * 改写为 moof 的 tfhd track_id。
     */
    private void mergeSegments(Path dir, int segCount, Path outputFile) throws Exception {
        List<Integer> videoIdxAll = new ArrayList<>();
        List<Integer> audioIdxAll = new ArrayList<>();
        for (int i = 1; i <= segCount; i++) {
            if ((i & 1) == 1) videoIdxAll.add(i);
            else audioIdxAll.add(i);
        }
        List<Integer> videoIdx = filterToLastInit(dir, videoIdxAll);
        List<Integer> audioIdx = filterToLastInit(dir, audioIdxAll);

        Path videoMp4 = dir.resolve("stream_video.mp4");
        Path audioMp4 = dir.resolve("stream_audio.mp4");
        boolean hasVideo = !videoIdx.isEmpty();
        boolean hasAudio = !audioIdx.isEmpty();

        if (hasVideo) {
            Path initFile = patchInitTrex(dir, videoIdx);
            Path binFile = binaryConcat(dir, initFile, videoIdx);
            runFfmpeg(List.of(ffmpegPath, "-y", "-i", binFile.toString(), "-c", "copy",
                    videoMp4.toString()));
        }
        if (hasAudio) {
            Path initFile = patchInitTrex(dir, audioIdx);
            Path binFile = binaryConcat(dir, initFile, audioIdx);
            runFfmpeg(List.of(ffmpegPath, "-y", "-i", binFile.toString(), "-c", "copy",
                    audioMp4.toString()));
        }

        // mux video + audio
        List<String> mux = new ArrayList<>();
        mux.add(ffmpegPath);
        mux.add("-y");
        if (hasVideo) mux.addAll(List.of("-i", videoMp4.toString()));
        if (hasAudio) mux.addAll(List.of("-i", audioMp4.toString()));
        mux.addAll(List.of("-c", "copy", "-movflags", "+faststart", outputFile.toString()));
        String muxLog = runFfmpeg(mux);
        if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
            throw new BusinessException("ffmpeg mux 合并失败: " + muxLog);
        }
    }

    /** 二进制拼接：init 段（含首个 moof+mdat）+ 后续所有 moof 段，输出为单个 fragmented MP4 字节流。 */
    private Path binaryConcat(Path dir, Path initFile, List<Integer> idxs) throws Exception {
        Path out = dir.resolve("stream_" + idxs.get(0) + ".bin");
        try (OutputStream os = Files.newOutputStream(out)) {
            if (initFile != null && Files.exists(initFile)) {
                Files.copy(initFile, os);
            }
            // 跳过 idx 0（init 段本身，已写入），从 1 开始拼接后续 moof 段
            for (int i = 1; i < idxs.size(); i++) {
                Path segFile = dir.resolve(String.format("seg-%05d.bin", idxs.get(i)));
                if (Files.exists(segFile)) Files.copy(segFile, os);
            }
        }
        return out;
    }

    /** 把 init 段（idxs[0]）的 trex track_id 改写为首个 moof 段（idxs[1]）的 tfhd track_id。
     *  worker.js 生成的 init trex track_id 与 moof tfhd track_id 不一致会导致 ffmpeg 解封装失败。
     *  返回改写后的临时文件路径；若无需改写（已一致或找不到 trex/tfhd）则返回原 init 文件路径。 */
    private Path patchInitTrex(Path dir, List<Integer> idxs) throws Exception {
        if (idxs.isEmpty()) return null;
        int initIdx = idxs.get(0);
        Path initFile = dir.resolve(String.format("seg-%05d.bin", initIdx));
        if (!Files.exists(initFile) || idxs.size() < 2) return initFile;

        byte[] initBytes = Files.readAllBytes(initFile);
        int trexOffset = findBoxOffset(initBytes, "trex");
        if (trexOffset < 0 || trexOffset + 16 > initBytes.length) return initFile;
        int trexTrackIdOffset = trexOffset + 12; // 8(size+type) + 4(version+flags)
        int currentTrackId = readBe32(initBytes, trexTrackIdOffset);

        Path moofFile = dir.resolve(String.format("seg-%05d.bin", idxs.get(1)));
        if (!Files.exists(moofFile)) return initFile;
        byte[] moofBytes = Files.readAllBytes(moofFile);
        int tfhdOffset = findBoxOffset(moofBytes, "tfhd");
        if (tfhdOffset < 0 || tfhdOffset + 12 > moofBytes.length) return initFile;
        int tfhdTrackId = readBe32(moofBytes, tfhdOffset + 8); // 8(size+type) → version+flags(4) → track_id

        if (currentTrackId == tfhdTrackId) return initFile;

        initBytes[trexTrackIdOffset]     = (byte) ((tfhdTrackId >>> 24) & 0xFF);
        initBytes[trexTrackIdOffset + 1] = (byte) ((tfhdTrackId >>> 16) & 0xFF);
        initBytes[trexTrackIdOffset + 2] = (byte) ((tfhdTrackId >>> 8) & 0xFF);
        initBytes[trexTrackIdOffset + 3] = (byte) (tfhdTrackId & 0xFF);
        Path patched = dir.resolve(String.format("seg-%05d-patched.bin", initIdx));
        Files.write(patched, initBytes);
        log.info("Patched trex track_id {} -> {} in seg-{}", currentTrackId, tfhdTrackId, initIdx);
        return patched;
    }

    /** 在字节数组中查找 ISO BMFF box 类型（4 字节 ASCII）的偏移量；找不到返回 -1。 */
    private static int findBoxOffset(byte[] bytes, String boxType) {
        if (bytes == null || bytes.length < 8 || boxType == null || boxType.length() != 4) return -1;
        byte b0 = (byte) boxType.charAt(0), b1 = (byte) boxType.charAt(1),
             b2 = (byte) boxType.charAt(2), b3 = (byte) boxType.charAt(3);
        for (int i = 0; i <= bytes.length - 8; i++) {
            if (bytes[i + 4] == b0 && bytes[i + 5] == b1 && bytes[i + 6] == b2 && bytes[i + 7] == b3) {
                return i;
            }
        }
        return -1;
    }

    private static int readBe32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
             | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    /** 找出该流（video 或 audio）的最后一个 init 段（ftyp/moov 首盒）位置，
     *  返回 [最后一个 init 的索引位置, 之后所有段]；若无 init，保留全部段。 */
    private List<Integer> filterToLastInit(Path dir, List<Integer> idxs) throws Exception {
        int lastInitPos = -1;
        for (int i = 0; i < idxs.size(); i++) {
            Path f = dir.resolve(String.format("seg-%05d.bin", idxs.get(i)));
            if (!Files.exists(f)) continue;
            try (var raf = new java.io.RandomAccessFile(f.toFile(), "r")) {
                if (raf.length() < 8) continue;
                byte[] header = new byte[8];
                raf.readFully(header);
                String boxType = new String(header, 4, 4, StandardCharsets.US_ASCII);
                if ("ftyp".equals(boxType) || "moov".equals(boxType)) {
                    lastInitPos = i;
                }
            }
        }
        if (lastInitPos == -1) return idxs;
        return new ArrayList<>(idxs.subList(lastInitPos, idxs.size()));
    }

    private String runFfmpeg(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();
        String procLog = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            log.warn("ffmpeg 执行失败 (code {}): {}", code, procLog.length() > 500 ? procLog.substring(0, 500) + "..." : procLog);
        }
        return procLog;
    }

    private static String resolveFfmpeg(String path) {
        if (path == null || path.isBlank()) return "ffmpeg";
        Path p = Path.of(path);
        if (Files.isDirectory(p)) {
            String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";
            return p.resolve(exe).toString();
        }
        return path;
    }

    /** 解析浏览器路径：显式配置 > 系统 Chrome > null（让 Playwright 用自带 chromium） */
    private static String resolveChromium(String configured) {
        if (configured != null && !configured.isBlank()) {
            if (Files.exists(Path.of(configured))) {
                return configured;
            }
            log.warn("配置的 chromium-path 不存在: {}", configured);
        }
        // Windows 系统常见 Chrome 位置
        String[] candidates = {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
        };
        for (String c : candidates) {
            if (c != null && Files.exists(Path.of(c))) {
                return c;
            }
        }
        return null;
    }

    private static String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) return "video";
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    private void cleanup(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var list = Files.list(dir)) {
            list.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
            Files.deleteIfExists(dir);
        } catch (Exception e) {
            log.debug("清理临时目录失败: {}", dir);
        }
    }

    // ===== 注入脚本 =====

    /** 在页面加载前注入：移除 webdriver 标记 + 拦截 SourceBuffer.appendBuffer（双保险：prototype + addSourceBuffer）
     *  同时通过 XHR.open 跟踪当前媒体类型（ad / content），仅捕获 content 段，过滤广告 */
    private static final String INIT_SCRIPT = """
            (() => {
              try {
                window.__sidecarInit = 'started';
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                window.__currentMediaType = 'unknown';

                /* 跟踪 XHR 请求的 URL，判断当前正在加载 ad 还是 content。
                 * 关键：CCTV 播放器会先预加载内容（Phase 1），然后播放广告，广告结束后再重新加载内容（Phase 3）。
                 * 如果两阶段都捕获，会产生重复段。所以默认禁用捕获，只在广告出现后再启用。
                 * 兜底：30s 内没检测到广告，也启用捕获（应对无广告的视频）。 */
                window.__captureEnabled = false;
                window.__adOccurred = false;
                window.__playerInitTime = Date.now();
                setTimeout(() => {
                  if (!window.__adOccurred) {
                    window.__captureEnabled = true;
                    console.log('[cctv-sidecar] 30s 无广告，启用捕获（兜底）');
                  }
                }, 30000);

                const origXhrOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                  try {
                    if (typeof url === 'string' && url) {
                      if (url.indexOf('/adv/hls/') !== -1
                          || url.indexOf('vda.v.qcloudcdn.com') !== -1
                          || url.indexOf('hlsvda.cntv.kcdnvip.com') !== -1) {
                        window.__currentMediaType = 'ad';
                        window.__adOccurred = true;
                        window.__captureEnabled = false; /* 广告期间禁用捕获 */
                      } else if (url.indexOf('/h5e/hls/') !== -1
                          || (url.indexOf('.ts') !== -1 && url.indexOf('/hls/') !== -1 && window.__currentMediaType !== 'ad')) {
                        window.__currentMediaType = 'content';
                        if (window.__adOccurred) {
                          /* 广告已结束，内容重新加载，启用捕获 */
                          if (!window.__captureEnabled) {
                            console.log('[cctv-sidecar] 广告结束，启用内容捕获');
                          }
                          window.__captureEnabled = true;
                        }
                      }
                    }
                  } catch (e) {}
                  return origXhrOpen.apply(this, arguments);
                };

                const wrapAppend = (sb) => {
                  if (!sb || !sb.appendBuffer) return;
                  /* 必须用 hasOwnProperty 检查 OWN 属性：prototype 已被包装，instance 会继承 __cctvWrapped=true
                   * 若直接 sb.__cctvWrapped 会读到 prototype 的值导致 instance 永不包装 */
                  if (Object.prototype.hasOwnProperty.call(sb, '__cctvWrapped')) return;
                  sb.__cctvWrapped = true;
                  /* 标记此 SourceBuffer 在创建时的媒体类型，用于 appendBuffer 时过滤 */
                  sb.__cctvMediaType = window.__currentMediaType || 'unknown';
                  console.log('[cctv-sidecar] wrapped SourceBuffer instance, type=' + sb.__cctvMediaType);
                  const orig = sb.appendBuffer;
                  sb.appendBuffer = function(data) {
                    try {
                      /* 用 this 读取调用实例的媒体类型（避免 closure sb 永远指向 prototype 的问题） */
                      const mt = this.__cctvMediaType || 'unknown';
                      let bytes;
                      if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
                      else if (ArrayBuffer.isView(data)) bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                      else bytes = new Uint8Array(data);
                      /* 判断首 box 类型：ftyp/moov=init segment，必须总是捕获
                       * （worker.js 在 Phase 1 预加载内容时就 appendBuffer 了 init 段（ftyp+moov），
                       * 但那时 __captureEnabled=false 会跳过，丢失 init 段会导致 ffmpeg 无法解封装。
                       * init 段首 box 是 ftyp（File Type Box），moov 是其后的第二个 box，
                       * 所以同时检查 ftyp 和 moov 才能覆盖 worker 的不同调用模式） */
                      const boxType = String.fromCharCode(bytes[4], bytes[5], bytes[6], bytes[7]);
                      if (boxType === 'ftyp' || boxType === 'moov' || (mt === 'content' && window.__captureEnabled)) {
                        let bin = '';
                        const CHUNK = 0x8000;
                        for (let i = 0; i < bytes.length; i += CHUNK) {
                          bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
                        }
                        window.__captureSegment(btoa(bin));
                        window.__segCount = (window.__segCount || 0) + 1;
                        window.__lastSegTime = Date.now();
                      } else if (mt === 'ad') {
                        window.__adSegCount = (window.__adSegCount || 0) + 1;
                        if ((window.__adSegCount % 5) === 1) {
                          console.log('[cctv-sidecar] ad segment skipped #' + window.__adSegCount);
                        }
                      }
                    } catch (e) {
                      console.error('[cctv-sidecar] capture error', e);
                    }
                    return orig.call(this, data);
                  };
                };

                if (window.SourceBuffer && window.SourceBuffer.prototype) {
                  /* 包装 prototype 作为兜底（未走 addSourceBuffer 的 SB 也能被拦截）；
                   * 不在 prototype 上设 __cctvWrapped，避免影响 instance 的 hasOwnProperty 检查 */
                  const protoSb = window.SourceBuffer.prototype;
                  const origProtoAppend = protoSb.appendBuffer;
                  protoSb.appendBuffer = function(data) {
                    try {
                      /* 防止双重捕获：若 instance 已被 wrapAppend 包装（有 own __cctvMediaType），
                       * 捕获逻辑已由 instance wrapper 完成，这里直接调原生即可。
                       * 否则 wrapAppend 内 orig=proto wrapper 会再次触发本段逻辑造成重复写盘。 */
                      if (Object.prototype.hasOwnProperty.call(this, '__cctvMediaType')) {
                        return origProtoAppend.call(this, data);
                      }
                      const mt = this.__cctvMediaType || 'unknown';
                      let bytes;
                      if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
                      else if (ArrayBuffer.isView(data)) bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                      else bytes = new Uint8Array(data);
                      const boxType = String.fromCharCode(bytes[4], bytes[5], bytes[6], bytes[7]);
                      if (boxType === 'ftyp' || boxType === 'moov' || (mt === 'content' && window.__captureEnabled)) {
                        let bin = '';
                        const CHUNK = 0x8000;
                        for (let i = 0; i < bytes.length; i += CHUNK) {
                          bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
                        }
                        window.__captureSegment(btoa(bin));
                        window.__segCount = (window.__segCount || 0) + 1;
                        window.__lastSegTime = Date.now();
                      } else if (mt === 'ad') {
                        window.__adSegCount = (window.__adSegCount || 0) + 1;
                        if ((window.__adSegCount % 5) === 1) {
                          console.log('[cctv-sidecar] ad segment skipped #' + window.__adSegCount);
                        }
                      }
                    } catch (e) {
                      console.error('[cctv-sidecar] proto capture error', e);
                    }
                    return origProtoAppend.call(this, data);
                  };
                  window.__sidecarWrappedProto = true;
                }
                if (window.MediaSource && window.MediaSource.prototype) {
                  const origAdd = window.MediaSource.prototype.addSourceBuffer;
                  window.MediaSource.prototype.addSourceBuffer = function() {
                    const sb = origAdd.apply(this, arguments);
                    console.log('[cctv-sidecar] addSourceBuffer called', arguments[0], 'type=' + window.__currentMediaType);
                    wrapAppend(sb);
                    return sb;
                  };
                  window.__sidecarWrappedMS = true;
                }
                window.__sidecarInit = 'done';
                console.log('[cctv-sidecar] INIT_SCRIPT applied, proto=' + window.__sidecarWrappedProto + ', ms=' + window.__sidecarWrappedMS);
              } catch (e) {
                window.__sidecarError = e.message;
                console.log('[cctv-sidecar] INIT error: ' + e.message);
              }
            })();
            """;

    /** 诊断脚本：返回 video 元素状态、MediaSource/SourceBuffer 是否使用、hls.js 是否加载、sidecar 标记 */
    private static final String DIAG_SCRIPT = """
            () => {
              const v = document.querySelector('video');
              const out = {
                videoExists: !!v,
                videoSrc: v ? v.src : null,
                videoCurrentSrc: v ? v.currentSrc : null,
                readyState: v ? v.readyState : null,
                networkState: v ? v.networkState : null,
                duration: v ? v.duration : null,
                currentTime: v ? v.currentTime : null,
                paused: v ? v.paused : null,
                mse: typeof MediaSource,
                sourceBuffer: typeof SourceBuffer,
                hlsExists: typeof window.hls !== 'undefined' ? typeof window.hls : 'undefined',
                error: v && v.error ? v.error.code : null,
                sidecarInit: window.__sidecarInit,
                sidecarError: window.__sidecarError,
                sidecarWrappedProto: window.__sidecarWrappedProto,
                sidecarWrappedMS: window.__sidecarWrappedMS,
                sbProtoWrapped: !!(window.SourceBuffer && window.SourceBuffer.prototype && window.SourceBuffer.prototype.__cctvWrapped),
                sbProtoAppendIsNative: window.SourceBuffer && window.SourceBuffer.prototype
                  ? (window.SourceBuffer.prototype.appendBuffer || '').toString().indexOf('[native code]') !== -1
                  : null
              };
              return JSON.stringify(out);
            }
            """;

    /** 视频元素出现后注入：静音、自动播放、播放稳定后切到 8x */
    private static final String START_PLAY_SCRIPT = """
            () => {
              const v = document.querySelector('video');
              if (!v) return;
              v.muted = true;
              v.defaultMuted = true;
              /* 起步用 1x，等真正 playing 后再切 8x，避免缓冲耗尽触发 player 重载 */
              v.play().catch(e => console.error('[cctv-sidecar] play error', e));
              v.addEventListener('playing', () => {
                try { v.playbackRate = 2; } catch (e) {}
                console.log('[cctv-sidecar] playing, rate=2');
              }, { once: false });
              v.addEventListener('ratechange', () => {
                if (v.playbackRate !== 2 && !v.paused) {
                  try { v.playbackRate = 2; } catch (e) {}
                }
              });
              /* 兜底：每 2s 检查，若长时间暂停则尝试恢复 */
              setInterval(() => {
                if (v.paused && v.readyState >= 2) {
                  v.play().catch(() => {});
                }
              }, 2000);
            }
            """;

    /** 等待视频完成：段流停止 60s 且至少 5 段，或视频自然结束且至少 5 段 */
    private static final String WAIT_DONE_SCRIPT = """
            () => {
              const v = document.querySelector('video');
              if (!v) return false;
              const segCount = window.__segCount || 0;
              const now = Date.now();
              const lastSegAgo = now - (window.__lastSegTime || 0);
              /* 段流停止 60s 且至少 5 段：认定结束（播放完成或卡死） */
              if (segCount >= 5 && lastSegAgo > 60000) return true;
              /* 真正自然结束：duration 有效 + currentTime 接近末尾 + 至少 5 段 */
              if (segCount >= 5 && isFinite(v.duration) && v.duration > 0
                  && v.readyState >= 4
                  && (v.ended || v.currentTime >= v.duration - 0.5)) return true;
              return false;
            }
            """;
}
