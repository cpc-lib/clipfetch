package com.fvd.parser.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.parser.domain.FormatInfo;
import com.fvd.parser.domain.Platform;
import com.fvd.parser.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * yt-dlp 封装层：视频解析 / 直链获取 / 下载命令构建
 */
@Slf4j
@Service
public class YtDlpService {

    private final String ytdlpPath;
    private final String ffmpegLocation;
    private final String proxy;
    private final String jsRuntimePath;
    private final String aria2cPath;
    private final int aria2cConnections;
    private final int parseTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 平台直连可达性缓存：key=Platform，value=是否可直连 */
    private final Map<Platform, Boolean> directAccessCache = new ConcurrentHashMap<>();
    /** 需要代理的被墙平台（在美国网络下可达，在大陆网络下不可达） */
    private static final Set<Platform> PROXYABLE_PLATFORMS = Set.of(
            Platform.YOUTUBE, Platform.TWITTER, Platform.TIKTOK, Platform.INSTAGRAM,
            Platform.BBC, Platform.SPANKBANG, Platform.AMASIAN_TV, Platform.XVIDEOS,
            Platform.PORNHUB);
    /** 平台直连检测目标 URL */
    private static final Map<Platform, String> PLATFORM_PROBE_URLS = Map.of(
            Platform.YOUTUBE, "https://www.youtube.com",
            Platform.TWITTER, "https://x.com",
            Platform.TIKTOK, "https://www.tiktok.com",
            Platform.INSTAGRAM, "https://www.instagram.com",
            Platform.BBC, "https://www.bbc.com",
            Platform.SPANKBANG, "https://spankbang.com",
            Platform.AMASIAN_TV, "https://amasian.tv",
            Platform.XVIDEOS, "https://www.xvideos.com",
            Platform.PORNHUB, "https://www.pornhub.com");

    public YtDlpService(@Value("${app.ytdlp-path}") String ytdlpPath,
                        @Value("${app.ffmpeg-location:}") String ffmpegLocation,
                        @Value("${app.js-runtime-path:}") String jsRuntimePath,
                        @Value("${app.aria2c-path:}") String aria2cPath,
                        @Value("${app.aria2c-connections:16}") int aria2cConnections,
                        @Value("${app.proxy:}") String proxy,
                        @Value("${app.parse-timeout:60}") int parseTimeout) {
        // ytdlpPath 可能是目录（如 D:\clipfetch\package\yt-dlp），目录时拼接 yt-dlp.exe；
        // 与 resolveExecutable 对 aria2c 的处理保持一致，避免 CreateProcess error=5
        String resolvedYtdlp = resolveExecutable(ytdlpPath, "yt-dlp.exe");
        this.ytdlpPath = resolvedYtdlp != null ? resolvedYtdlp : ytdlpPath;
        this.ffmpegLocation = ffmpegLocation;
        this.jsRuntimePath = jsRuntimePath;
        this.aria2cPath = aria2cPath;
        this.aria2cConnections = aria2cConnections;
        this.proxy = proxy;
        this.parseTimeout = parseTimeout;
    }

    // ===== 解析 =====

    public static String humanSize(long bytes) {
        if (bytes >= 1L << 30) return String.format("%.1fGB", bytes / 1073741824.0);
        if (bytes >= 1L << 20) return String.format("%.1fMB", bytes / 1048576.0);
        if (bytes >= 1L << 10) return String.format("%.0fKB", bytes / 1024.0);
        return bytes + "B";
    }

    public static String formatDuration(long seconds) {
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    // ===== 直链 =====

    /**
     * @param userCookieContent 当前登录用户上传的 cookies.txt 原文（抖音/Instagram），null 表示无
     */
    public VideoInfo parse(String url, String userCookieContent) {
        java.nio.file.Path tempCookie = materializeUserCookies(userCookieContent);
        try {
            List<String> cmd = baseArgs(url, tempCookie);
            cmd.add("--dump-single-json");
            cmd.add("--socket-timeout");
            cmd.add("90");
            cmd.add(url);
            String stdout = execute(cmd, parseTimeout);
            JsonNode info = readJson(stdout);
            return mapVideoInfo(info);
        } finally {
            deleteQuietly(tempCookie);
        }
    }

    // ===== 下载命令（供 DownloadService 使用）=====

    /**
     * 拿到完整 JSON（解析 + 字幕提取复用）
     */
    public JsonNode dumpInfo(String url, String userCookieContent) {
        return dumpInfo(url, userCookieContent, List.of());
    }

    /**
     * 拿到完整 JSON，并为需要浏览器模拟或特殊请求头的站点追加参数。
     */
    public JsonNode dumpInfo(String url, String userCookieContent, List<String> extraArgs) {
        java.nio.file.Path tempCookie = materializeUserCookies(userCookieContent);
        try {
            List<String> cmd = baseArgs(url, tempCookie);
            cmd.add("--dump-single-json");
            cmd.add("--socket-timeout");
            cmd.add("15");
            if (extraArgs != null && !extraArgs.isEmpty()) {
                cmd.addAll(extraArgs);
            }
            cmd.add(url);
            return readJson(execute(cmd, parseTimeout));
        } finally {
            deleteQuietly(tempCookie);
        }
    }

    public String directUrl(String url, String formatId, String userCookieContent) {
        if (formatId != null && formatId.contains("+")) {
            throw new BusinessException("该清晰度为音视频分离格式，无法获取直链，请使用服务端下载");
        }
        java.nio.file.Path tempCookie = materializeUserCookies(userCookieContent);
        try {
            List<String> cmd = baseArgs(url, tempCookie);
            cmd.add("-f");
            cmd.add(formatId);
            cmd.add("--get-url");
            cmd.add(url);
            String stdout = execute(cmd, parseTimeout);
            for (String line : stdout.split("\\R")) {
                line = line.trim();
                if (line.startsWith("http")) {
                    return line;
                }
            }
            throw new BusinessException("获取直链失败，请尝试服务端下载");
        } finally {
            deleteQuietly(tempCookie);
        }
    }

    public List<String> buildDownloadCmd(String url, String formatId, String outputPathPattern,
                                         java.nio.file.Path userCookieFile) {
        return buildDownloadCmd(url, formatId, outputPathPattern, userCookieFile, List.of());
    }

    public List<String> buildDownloadCmd(String url, String formatId, String outputPathPattern,
                                         java.nio.file.Path userCookieFile, List<String> extraArgs) {
        List<String> cmd = baseArgs(url, userCookieFile);
        if (formatId != null && !formatId.isBlank()) {
            cmd.add("-f");
            cmd.add(formatId);
        } else {
            cmd.add("-f");
            cmd.add("bv*+ba/b");
        }
        if (formatId != null && formatId.contains("+")) {
            cmd.add("--merge-output-format");
            cmd.add("mp4");
        }
        cmd.add("--no-mtime");
        cmd.add("--no-part");
        // 进度行逐行输出 + 固定模板，供 DownloadService 解析后通过 WebSocket 推送给前端
        cmd.add("--newline");
        cmd.add("--progress-template");
        // 末尾两列为 HLS/DASH 分片序号/总数（非分片下载为 NA），供后端在 total 未知时推算百分比
        cmd.add("download:FVDPROG|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.speed)s|%(progress.fragment_index)s|%(progress.fragment_count)s");
        // aria2c 多连接分片下载（每服务器 N 连接 / N 分片 / 2MB 块），显著加速被单连接限速的 CDN。
        // 注意：--downloader 直接传可执行文件完整路径（yt-dlp 按 basename 匹配识别为 aria2c），
        // 不要写成 aria2c:路径，否则 basename 匹配失败会静默回落到原生单连接下载
        String aria2c = resolveExecutable(aria2cPath, "aria2c.exe");
        if (aria2c != null) {
            cmd.add("--downloader");
            cmd.add(aria2c);
            cmd.add("--downloader-args");
            // 外部下载器时 --progress-template 不生效，aria2c 自带的 [#xx 1.5MiB/10MiB(15%) ...] 进度行
            // 由 DownloadService 另行解析
            cmd.add("aria2c:-x " + aria2cConnections + " -s " + aria2cConnections
                    + " -k 8M --file-allocation=none --console-log-level=warn --summary-interval=1 --show-console-readout=true");
        }
        if (ffmpegLocation != null && !ffmpegLocation.isBlank()) {
            cmd.add("--ffmpeg-location");
            cmd.add(ffmpegLocation);
        }
        // 额外 yt-dlp 参数（如 HLS 并发分片 -N），置于 URL 之前
        if (extraArgs != null && !extraArgs.isEmpty()) {
            cmd.addAll(extraArgs);
        }
        cmd.add("-o");
        cmd.add(outputPathPattern);
        cmd.add(url);
        return cmd;
    }

    // ===== 内部 =====

    public String ytdlpPath() {
        return ytdlpPath;
    }

    public String proxy() {
        return proxy;
    }

    /**
     * 判断平台是否需要走代理（供其他 Parser 复用）。
     */
    public boolean needsProxyFor(Platform platform) {
        return needsProxy(platform);
    }

    /**
     * 平台是否可直连（结果缓存）。供 Parser 在发起请求前判断网络可达性。
     */
    public boolean isReachable(Platform platform) {
        return isPlatformReachable(platform);
    }

    /**
     * 检测目标平台是否可直连（HTTP GET 首页，3 秒超时），结果缓存。
     * 不需要代理的平台始终返回 true；被墙平台检测一次后缓存。
     */
    private boolean isPlatformReachable(Platform platform) {
        if (!PROXYABLE_PLATFORMS.contains(platform)) {
            return true; // 国内平台始终直连
        }
        return directAccessCache.computeIfAbsent(platform, p -> {
            String probeUrl = PLATFORM_PROBE_URLS.get(p);
            if (probeUrl == null) return false;
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(java.net.URI.create(probeUrl))
                        .timeout(Duration.ofSeconds(3))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 400;
                // 检测地理封禁重定向：部分站点（如 Amasian TV）对被禁地区返回 307 跳转到
                // unavailable-region 页面，该页面本身返回 200，会被误判为可达。
                if (ok) {
                    String finalUri = resp.uri().toString().toLowerCase();
                    String body = resp.body();
                    boolean geoBlocked = finalUri.contains("unavailable")
                            || finalUri.contains("region-block")
                            || (body != null && (body.contains("not available in your region")
                            || body.contains("unavailable region")));
                    if (geoBlocked) {
                        ok = false;
                        log.info("平台直连检测: {} -> 地理封禁重定向 ({})", p, resp.uri());
                    }
                }
                log.info("平台直连检测: {} -> HTTP {} ({})", p, resp.statusCode(), ok ? "可达" : "不可达");
                return ok;
            } catch (Exception e) {
                log.warn("平台直连检测失败: {} ({})", p, e.getMessage());
                return false;
            }
        });
    }

    /**
     * 判断当前平台是否需要走代理：被墙平台不可达且代理已配置时返回 true。
     */
    private boolean needsProxy(Platform platform) {
        if (proxy == null || proxy.isBlank()) {
            return false; // 无代理配置，无论如何都不走代理
        }
        return PROXYABLE_PLATFORMS.contains(platform) && !isPlatformReachable(platform);
    }

    private List<String> baseArgs(String url, java.nio.file.Path userCookieFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ytdlpPath);
        cmd.add("--no-playlist");
        cmd.add("--no-warnings");
        // 登录用户上传的 cookies（临时文件），未传则不带 cookies
        Platform platform = Platform.from(url);
        String cookiePath = null;
        if (userCookieFile != null) {
            cookiePath = userCookieFile.toAbsolutePath().normalize().toString();
        }
        if (cookiePath != null) {
            cmd.add("--cookies");
            cmd.add(cookiePath);
        }
        // 被墙平台不可达时才走代理；可达（如美国网络）或国内平台始终直连
        if (needsProxy(platform)) {
            cmd.add("--proxy");
            cmd.add(proxy);
        }
        // YouTube 2026 反爬：需要 JS 运行时解 BotGuard 挑战 + PO token 脚本 provider（deno）
        if (platform == Platform.YOUTUBE && jsRuntimePath != null && !jsRuntimePath.isBlank()) {
            cmd.add("--js-runtimes");
            cmd.add("deno:" + jsRuntimePath);
            cmd.add("--remote-components");
            cmd.add("ejs:github");
        }
        // SpankBang 的 Cloudflare 页面需要 yt-dlp 使用可用的浏览器指纹。
        if (platform == Platform.SPANKBANG) {
            cmd.add("--impersonate");
            cmd.add("chrome");
        }
        return cmd;
    }

    /**
     * 用户 cookies 原文写入临时文件供 yt-dlp --cookies 使用；null/空返回 null。
     * 由 CookieService 提供同能力，这里直接复用，避免 service 间循环依赖。
     */
    public java.nio.file.Path materializeCookieFile(String content) {
        return materializeUserCookies(content);
    }

    private java.nio.file.Path materializeUserCookies(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path file = Files.createTempFile("fvd-ytdlp-cookie-", ".txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception e) {
            throw new BusinessException("cookies 临时文件写入失败：" + e.getMessage());
        }
    }

    private void deleteQuietly(java.nio.file.Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * JS 运行时配置可能是 deno.exe 的完整路径，也可能是目录；返回所在目录用于注入 PATH。
     */
    private String denoDirectory() {
        if (jsRuntimePath == null || jsRuntimePath.isBlank()) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Path.of(jsRuntimePath);
        if (java.nio.file.Files.isRegularFile(p)) {
            return p.getParent().toAbsolutePath().normalize().toString();
        }
        if (java.nio.file.Files.isDirectory(p)) {
            return p.toAbsolutePath().normalize().toString();
        }
        return null;
    }

    /**
     * 解析外部可执行文件：配置可为 exe 完整路径或所在目录；找不到返回 null（回退 yt-dlp 内置下载器）。
     */
    private String resolveExecutable(String configured, String exeName) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Path.of(configured);
        if (java.nio.file.Files.isRegularFile(p)) {
            return p.toAbsolutePath().normalize().toString();
        }
        if (java.nio.file.Files.isDirectory(p)) {
            java.nio.file.Path exe = p.resolve(exeName);
            if (java.nio.file.Files.isRegularFile(exe)) {
                return exe.toAbsolutePath().normalize().toString();
            }
        }
        log.warn("配置的可执行文件不存在：{}", configured);
        return null;
    }

    /**
     * 供 DownloadService 给子进程注入 deno/aria2c 所在目录
     */
    public void enhanceEnvironment(ProcessBuilder pb) {
        StringBuilder extra = new StringBuilder();
        String denoDir = denoDirectory();
        if (denoDir != null) {
            extra.append(denoDir);
        }
        String aria2c = resolveExecutable(aria2cPath, "aria2c.exe");
        if (aria2c != null) {
            String dir = java.nio.file.Path.of(aria2c).getParent().toString();
            if (!extra.isEmpty()) {
                extra.append(java.io.File.pathSeparator);
            }
            extra.append(dir);
        }
        if (!extra.isEmpty()) {
            String pathEnv = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", extra + java.io.File.pathSeparator + pathEnv);
        }
    }

    public String execute(List<String> cmd, int timeoutSeconds) {
        log.info("执行 yt-dlp: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        // bgutil 脚本式 PO token provider 通过 PATH 查找 deno
        enhanceEnvironment(pb);
        Process process = null;
        try {
            process = pb.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outThread = drain(process.getInputStream(), stdout);
            Thread errThread = drain(process.getErrorStream(), stderr);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("解析超时，请稍后重试");
            }
            outThread.join(3000);
            errThread.join(3000);
            if (process.exitValue() != 0) {
                String err = firstError(stderr.toString());
                log.warn("yt-dlp 失败 (code {}): {}", process.exitValue(), err);
                throw new BusinessException(err);
            }
            return stdout.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new BusinessException("操作被中断");
        } catch (Exception e) {
            throw new BusinessException("调用 yt-dlp 失败：" + e.getMessage());
        }
    }

    private Thread drain(java.io.InputStream is, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sink.append(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private String firstError(String stderr) {
        for (String line : stderr.split("\\R")) {
            line = line.trim();
            if (line.startsWith("ERROR:")) {
                String msg = line.substring(6).trim();
                if (msg.contains("Unsupported URL")) {
                    return "暂不支持该链接，请检查 URL 是否正确";
                }
                if (msg.contains("is not a valid URL")) {
                    return "链接格式不正确，请检查后重试";
                }
                if (msg.contains("Fresh cookies")) {
                    return "登录 cookies 已失效或缺失：请在「我的 Cookies」中重新上传该平台页面导出的 cookies.txt";
                }
                if (msg.contains("Sign in to confirm")) {
                    return "YouTube 验证未通过：请重新导出 youtube.com 的 cookies.txt，或切换代理节点后重试";
                }
                return msg.length() > 200 ? msg.substring(0, 200) : msg;
            }
        }
        return "解析失败，请检查链接或稍后重试";
    }

    public JsonNode readJson(String stdout) {
        try {
            int start = stdout.indexOf('{');
            if (start < 0) {
                throw new BusinessException("解析结果为空，该链接可能不支持");
            }
            return mapper.readTree(stdout.substring(start));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("解析视频信息失败");
        }
    }

    private VideoInfo mapVideoInfo(JsonNode info) {
        List<FormatInfo> formats = mapFormats(info.path("formats"));
        // 构造清晰度组合（音视频分离时生成 bv+ba 候选）
        formats = withCombinationFormats(formats);

        // YouTube/Twitter/TikTok 直链在被墙 CDN 上，浏览器直连不可达；抖音 CDN 直链仅内联播放、
        // 无 Content-Disposition，location.href 不会触发保存：均强制服务端代理下载
        Platform platform = Platform.from(info.path("webpage_url").asText(""));
        boolean serverOnly = platform == Platform.YOUTUBE || platform == Platform.TWITTER
                || platform == Platform.TIKTOK || platform == Platform.DOUYIN
                || platform == Platform.SPANKBANG;
        if (serverOnly) {
            formats = formats.stream().map(f -> new FormatInfo(
                    f.formatId(), f.ext(), f.resolution(), f.height(), f.filesize(),
                    f.filesizeApprox(), f.vcodec(), f.acodec(), f.label(),
                    f.needsMerge(), f.audioOnly(), true)).toList();
        }

        // 人工字幕与自动字幕合并（人工在前），再按中/英/日/韩优先级排序
        List<String> manualLangs = subtitleLangs(info.path("subtitles"));
        List<String> autoLangs = subtitleLangs(info.path("automatic_captions"));
        List<String> subtitleLangs = mergeSubtitleLangs(manualLangs, autoLangs);
        log.info("字幕轨道: {} 人工字幕{}条 自动字幕{}条 采用{}",
                info.path("id").asText(""), manualLangs.size(), autoLangs.size(), subtitleLangs);

        long duration = info.path("duration").asLong(0);
        return new VideoInfo(
                info.path("id").asText(null),
                info.path("title").asText("未知标题"),
                info.path("thumbnail").asText(null),
                duration > 0 ? duration : null,
                duration > 0 ? formatDuration(duration) : null,
                info.path("uploader").asText(null),
                Platform.from(info.path("webpage_url").asText("")).display,
                info.path("view_count").isNumber() ? info.path("view_count").asLong() : null,
                info.path("upload_date").asText(null),
                formats,
                null,
                subtitleLangs,
                !subtitleLangs.isEmpty()
        );
    }

    private List<FormatInfo> mapFormats(JsonNode formatsNode) {
        List<FormatInfo> result = new ArrayList<>();
        if (!formatsNode.isArray()) {
            return result;
        }
        for (JsonNode f : formatsNode) {
            String vcodec = f.path("vcodec").asText("none");
            String acodec = f.path("acodec").asText("none");
            boolean audioOnly = "none".equals(vcodec);  // 无视频流 → 纯音频
            boolean videoOnly = "none".equals(acodec);  // 无音频流 → 纯视频（需合并）
            if (audioOnly && videoOnly) {
                // vcodec/acodec 均未知（Twitter 的 http-* 格式两个字段都是 null）：
                // 有 resolution（形如 480x270）则为普通带音视频格式，否则是 storyboard 跳过
                String res = f.path("resolution").asText("");
                if (!res.contains("x")) {
                    continue;
                }
                audioOnly = false;
                videoOnly = false;
            }
            String protocol = f.path("protocol").asText("https");
            // HLS 不能作为普通文件直链下载，但 yt-dlp 可以在服务端下载并合并分片。
            boolean serverOnly = protocol.contains("m3u8");
            String ext = f.path("ext").asText("mp4");
            Integer height = f.path("height").isNumber() ? f.path("height").asInt() : null;
            Long filesize = f.path("filesize").isNumber() ? f.path("filesize").asLong() : null;
            Long filesizeApprox = f.path("filesize_approx").isNumber() ? f.path("filesize_approx").asLong() : null;
            String formatId = f.path("format_id").asText();
            String label;
            if (audioOnly) {
                int abr = (int) f.path("abr").asDouble(0);
                label = "音频 " + ext.toUpperCase() + (abr > 0 ? " (" + abr + "kbps)" : "");
            } else {
                label = (height != null ? height + "p" : f.path("resolution").asText("未知")) + " " + ext.toUpperCase();
                long size = filesize != null ? filesize : (filesizeApprox != null ? filesizeApprox : 0);
                if (size > 0) {
                    label += " (" + humanSize(size) + ")";
                }
            }
            result.add(new FormatInfo(formatId, ext,
                    f.path("resolution").asText(null), height, filesize, filesizeApprox,
                    "none".equals(vcodec) ? null : vcodec,
                    "none".equals(acodec) ? null : acodec,
                    label, !audioOnly && videoOnly, audioOnly, serverOnly));
        }
        return result;
    }

    /**
     * 针对音视频分离的站点（如 YouTube）生成 bv+ba 组合选项，按高度去重展示
     */
    private List<FormatInfo> withCombinationFormats(List<FormatInfo> raw) {
        List<FormatInfo> videos = raw.stream()
                .filter(f -> !f.audioOnly())
                .filter(f -> f.height() != null)
                .toList();
        Map<Integer, FormatInfo> bestByHeight = new LinkedHashMap<>();
        for (FormatInfo f : videos) {
            bestByHeight.merge(f.height(), f, (a, b) -> {
                boolean aBetter = score(a) > score(b);
                return aBetter ? a : b;
            });
        }
        List<FormatInfo> result = new ArrayList<>();
        List<Integer> heights = bestByHeight.keySet().stream().sorted(Comparator.reverseOrder()).toList();
        for (Integer h : heights) {
            FormatInfo best = bestByHeight.get(h);
            if (best.needsMerge()) {
                // 找到同高度 video-only + 最优音频，生成组合
                String audioId = raw.stream().filter(FormatInfo::audioOnly)
                        .max(Comparator.comparingDouble(f -> parseAbr(f)))
                        .map(FormatInfo::formatId).orElse(null);
                if (audioId != null) {
                    String comboId = best.formatId() + "+" + audioId;
                    long approxSize = (firstNonNull(best.filesize(), best.filesizeApprox(), 0L));
                    String label = h + "p " + best.ext().toUpperCase();
                    if (approxSize > 0) {
                        label += " (" + humanSize(approxSize) + ")";
                    }
                    result.add(new FormatInfo(comboId, best.ext(), best.resolution(), h,
                            best.filesize(), best.filesizeApprox(), best.vcodec(), "merged",
                            label, true, false));
                    continue;
                }
            }
            result.add(best);
        }
        // 音频选项：按音质去重取最好的两类（m4a 优先）
        List<FormatInfo> audios = raw.stream().filter(FormatInfo::audioOnly)
                .sorted(Comparator.comparingDouble((FormatInfo f) -> parseAbr(f)).reversed())
                .toList();
        Map<String, FormatInfo> bestAudioByExt = new LinkedHashMap<>();
        for (FormatInfo a : audios) {
            bestAudioByExt.putIfAbsent(a.ext(), a);
        }
        result.addAll(bestAudioByExt.values());
        return result;
    }

    private double score(FormatInfo f) {
        double s = f.height() != null ? f.height() : 0;
        if (f.ext() != null && f.ext().equals("mp4")) s += 5;
        if (f.vcodec() != null && f.vcodec().startsWith("avc")) s += 3;
        s += firstNonNull(f.filesize(), f.filesizeApprox(), 0L) / 1e9;
        return s;
    }

    private double parseAbr(FormatInfo f) {
        // label 中含 kbps
        try {
            String label = f.label();
            int i = label.indexOf('(');
            if (i >= 0 && label.contains("kbps")) {
                return Double.parseDouble(label.substring(i + 1, label.indexOf("kbps")).trim());
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private long firstNonNull(Long a, Long b, long def) {
        if (a != null && a > 0) return a;
        if (b != null && b > 0) return b;
        return def;
    }

    /** 字幕语言展示/下载优先级：简体中文 → 繁体中文 → 英文 → 日韩；其余语言排在后面 */
    private static final List<String> SUB_LANG_PRIORITY = List.of(
            "zh-Hans", "zh-CN", "zh-SG", "zh-Hant", "zh-TW", "zh-HK", "zh",
            "en-US", "en-GB", "en", "ja", "ko");
    private static final int MAX_SUBTITLE_LANGS = 30;

    /** 读取字幕轨道对象的全部语言代码（跳过 live_chat），保持原始顺序 */
    private static List<String> subtitleLangs(JsonNode trackMap) {
        List<String> langs = new ArrayList<>();
        if (trackMap != null && trackMap.isObject()) {
            trackMap.fieldNames().forEachRemaining(lang -> {
                if (!lang.contains("live_chat") && trackMap.path(lang).isArray()
                        && !trackMap.path(lang).isEmpty() && !langs.contains(lang)) {
                    langs.add(lang);
                }
            });
        }
        return langs;
    }

    /** 人工字幕在前、自动字幕补后去重，再按中/英/日/韩优先级稳定排序并限制数量 */
    private static List<String> mergeSubtitleLangs(List<String> manual, List<String> auto) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(manual);
        merged.addAll(auto);
        List<String> sorted = new ArrayList<>(merged);
        sorted.sort(Comparator.comparingInt(lang -> {
            int idx = SUB_LANG_PRIORITY.indexOf(lang);
            return idx >= 0 ? idx : SUB_LANG_PRIORITY.size();
        }));
        return sorted.size() > MAX_SUBTITLE_LANGS ? new ArrayList<>(sorted.subList(0, MAX_SUBTITLE_LANGS)) : sorted;
    }

    /**
     * 从 yt-dlp info JSON 中挑选一条字幕轨道用于独立下载：人工字幕优先于自动字幕，
     * 语言中文优先其次英文，同语言优先 vtt 格式。与 ai.SubtitleExtractor 的选择策略保持一致。
     */
    public static SubtitleTrack chooseSubtitleTrack(JsonNode info) {
        return chooseSubtitleTrack(info, null);
    }

    /**
     * 挑选字幕轨道。preferredLang 非空时优先精确匹配该语言（人工→自动），
     * 匹配不到再尝试其基础语言（如 zh-Hans → zh）；为空时按中文优先的默认策略选择。
     */
    public static SubtitleTrack chooseSubtitleTrack(JsonNode info, String preferredLang) {
        if (info == null) {
            return null;
        }
        if (preferredLang != null && !preferredLang.isBlank()) {
            SubtitleTrack track = pickTrackForLang(info.path("subtitles"), preferredLang, true);
            if (track != null) {
                return track;
            }
            return pickTrackForLang(info.path("automatic_captions"), preferredLang, false);
        }
        SubtitleTrack track = pickTrack(info.path("subtitles"), true);
        return track != null ? track : pickTrack(info.path("automatic_captions"), false);
    }

    /** 在指定轨道表中按语言精确匹配，找不到时尝试基础语言（去掉区域后缀） */
    private static SubtitleTrack pickTrackForLang(JsonNode trackMap, String preferredLang, boolean manual) {
        if (!trackMap.isObject() || trackMap.isEmpty()) {
            return null;
        }
        String lang = nonEmptyTrack(trackMap, preferredLang) ? preferredLang : null;
        if (lang == null) {
            int dash = preferredLang.indexOf('-');
            String base = dash > 0 ? preferredLang.substring(0, dash) : null;
            if (base != null && nonEmptyTrack(trackMap, base)) {
                lang = base;
            }
        }
        return lang == null ? null : buildTrack(trackMap, lang, manual);
    }

    private static SubtitleTrack pickTrack(JsonNode trackMap, boolean manual) {
        if (!trackMap.isObject() || trackMap.isEmpty()) {
            return null;
        }
        String lang = pickSubtitleLang(trackMap);
        if (lang == null) {
            return null;
        }
        return buildTrack(trackMap, lang, manual);
    }

    /** 从轨道表中选定语言的轨道列表里挑一条直链（优先 WebVTT） */
    private static SubtitleTrack buildTrack(JsonNode trackMap, String lang, boolean manual) {
        String vttUrl = null;
        String fallbackUrl = null;
        String fallbackExt = null;
        for (JsonNode t : trackMap.path(lang)) {
            String url = t.path("url").asText(null);
            if (url == null) {
                continue;
            }
            String ext = t.path("ext").asText("");
            if ("vtt".equals(ext)) {
                vttUrl = url;
                break;
            }
            if (fallbackUrl == null) {
                fallbackUrl = url;
                fallbackExt = ext;
            }
        }
        String url = vttUrl != null ? vttUrl : fallbackUrl;
        if (url == null) {
            return null;
        }
        String ext = vttUrl != null ? "vtt" : (fallbackExt == null || fallbackExt.isBlank() ? "vtt" : fallbackExt);
        return new SubtitleTrack(lang, ext, url, manual);
    }

    private static String pickSubtitleLang(JsonNode trackMap) {
        for (String lang : SUB_LANG_PRIORITY) {
            if (nonEmptyTrack(trackMap, lang)) {
                return lang;
            }
        }
        Iterator<String> it = trackMap.fieldNames();
        while (it.hasNext()) {  // 兜底：任意中文变体
            String lang = it.next();
            if (lang.startsWith("zh") && nonEmptyTrack(trackMap, lang)) {
                return lang;
            }
        }
        it = trackMap.fieldNames();
        while (it.hasNext()) {  // 再兜底：第一个可用语言
            String lang = it.next();
            if (!lang.contains("live_chat") && nonEmptyTrack(trackMap, lang)) {
                return lang;
            }
        }
        return null;
    }

    private static boolean nonEmptyTrack(JsonNode trackMap, String lang) {
        JsonNode tracks = trackMap.path(lang);
        return tracks.isArray() && !tracks.isEmpty();
    }

    /** 选中的字幕轨道：语言、格式、直链、是否人工字幕 */
    public record SubtitleTrack(String lang, String ext, String url, boolean manual) {
    }
}
