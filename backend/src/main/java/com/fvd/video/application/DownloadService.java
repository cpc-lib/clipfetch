package com.fvd.video.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.infrastructure.service.DownloadProgressHandler;
import com.fvd.video.infrastructure.service.YtDlpService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 服务端代理下载：yt-dlp 下载到临时目录，再流式返回给浏览器，最后清理临时文件
 */
@Slf4j
@Service
public class DownloadService {

    // yt-dlp --progress-template 输出：FVDPROG|<downloaded>|<total>|<speed>|<fragIndex>|<fragCount>（未知为 NA）
    private static final Pattern FVD_PROG = Pattern.compile(
            "^FVDPROG\\|(\\S+)\\|(\\S+)\\|(\\S+)\\|(\\S+)\\|(\\S+)$");
    // aria2c 外部下载器输出：[#2085b8 1.5MiB/6.6MiB(13%) CN:16 DL:0.9MiB ETA:5s]
    private static final Pattern ARIA2_PROG = Pattern.compile(
            "\\[#\\w+\\s+([\\d.]+\\w+)/([\\d.]+\\w+)\\(\\d+%\\).*?DL:([\\d.]+\\w+)");
    // 已含媒体扩展名的文件名直接使用，不再按 Content-Type 追加扩展名
    private static final Pattern MEDIA_EXT_PATTERN = Pattern.compile(
            "\\.(mp4|m4v|webm|mp3|flac|m4a|ogg|opus|wav|aac)$", Pattern.CASE_INSENSITIVE);
    private final YtDlpService ytDlp;
    private final DownloadProgressHandler progress;
    private final String downloadsDir;

    public DownloadService(YtDlpService ytDlp,
                           DownloadProgressHandler progress,
                           @Value("${app.downloads-dir:downloads}") String downloadsDir) {
        this.ytDlp = ytDlp;
        this.progress = progress;
        this.downloadsDir = downloadsDir;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1; // NA
        }
    }

    /**
     * 解析 aria2c 大小字符串（1.5MiB / 200KiB / 10B）为字节数
     */
    static long parseSize(String s) {
        try {
            int i = 0;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            double v = Double.parseDouble(s.substring(0, i));
            String unit = s.substring(i);
            long factor = switch (unit) {
                case "KiB" -> 1L << 10;
                case "MiB" -> 1L << 20;
                case "GiB" -> 1L << 30;
                default -> 1;
            };
            return (long) (v * factor);
        } catch (Exception e) {
            return -1;
        }
    }

    public void downloadToResponse(String url, String formatId, String title,
                                   HttpServletResponse response, String userCookieContent) {
        downloadToResponse(url, formatId, title, response, userCookieContent, null);
    }

    public void downloadToResponse(String url, String formatId, String title,
                                   HttpServletResponse response, String userCookieContent, String taskId) {
        downloadToResponse(url, formatId, title, response, userCookieContent, taskId, List.of());
    }

    /**
     * @param extraYtdlpArgs 追加到 yt-dlp 的额外参数（如 HLS 并发分片 -N 8）
     */
    public void downloadToResponse(String url, String formatId, String title,
                                   HttpServletResponse response, String userCookieContent, String taskId,
                                   List<String> extraYtdlpArgs) {
        Path dir = null;
        try {
            dir = Files.createDirectories(Path.of(downloadsDir, UUID.randomUUID().toString()));
            Path file = downloadVideoToFile(url, formatId, dir, userCookieContent, taskId, extraYtdlpArgs);
            streamFile(file, title, response, taskId);
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("下载被中断");
        } catch (IOException e) {
            throw new BusinessException("下载失败：" + e.getMessage());
        } finally {
            cleanup(dir);
        }
    }

    /**
     * 单独下载 HLS 字幕为 .vtt 文件并流式返回。
     * 用 yt-dlp 从 master playlist 发现并并发下载字幕分片（-N 16），
     * 比 ffmpeg 顺序下载快数倍（500+ 分片场景：yt-dlp ~10s vs ffmpeg ~90s）。
     *
     * @param masterUrl HLS master playlist URL（yt-dlp 从中发现字幕轨道）
     */
    public void downloadSubtitleToResponse(String masterUrl, String title,
                                           HttpServletResponse response) {
        Path dir = null;
        try {
            dir = Files.createDirectories(Path.of(downloadsDir, UUID.randomUUID().toString()));
            Path output = dir.resolve("subtitle.%(ext)s");

            List<String> cmd = new ArrayList<>();
            cmd.add(ytDlp.ytdlpPath());
            cmd.add("--skip-download");
            cmd.add("--write-subs");
            cmd.add("--sub-langs");
            cmd.add("en");
            cmd.add("--sub-format");
            cmd.add("vtt");
            cmd.add("-N");
            cmd.add("16");
            cmd.add("-o");
            cmd.add(output.toString());
            cmd.add(masterUrl);

            log.info("开始下载字幕: {}", masterUrl);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            ytDlp.enhanceEnvironment(pb);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            Thread drainThread = drain(process.getInputStream(), out);
            boolean finished = process.waitFor(180, TimeUnit.SECONDS);
            drainThread.join(3000);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("字幕下载超时");
            }
            if (process.exitValue() != 0) {
                log.warn("yt-dlp 字幕下载失败 (exit={}): {}", process.exitValue(), out);
                throw new BusinessException("字幕下载失败，请稍后重试");
            }

            // yt-dlp 输出文件名为 subtitle.en.vtt（语言代码后缀）
            Path subFile;
            try (Stream<Path> list = Files.list(dir)) {
                subFile = list.filter(p -> p.toString().endsWith(".vtt"))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException("字幕下载失败：未生成文件"));
            }

            String filename = sanitizeTitle(title) + ".vtt";
            long size = Files.size(subFile);

            response.setContentType("text/vtt; charset=utf-8");
            response.setContentLengthLong(size);
            response.setHeader("Content-Disposition",
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
            response.setHeader("Cache-Control", "no-store");

            try (OutputStream outStream = response.getOutputStream()) {
                Files.copy(subFile, outStream);
                outStream.flush();
            }
            log.info("字幕下载完成: {} ({} bytes)", filename, size);
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("字幕下载被中断");
        } catch (IOException e) {
            throw new BusinessException("字幕下载失败：" + e.getMessage());
        } finally {
            cleanup(dir);
        }
    }

    /**
     * YouTube 独立字幕下载：用 yt-dlp dumpInfo 拿到字幕轨道直链后直接 HTTP 下载返回。
     * 人工字幕优先于自动字幕，语言简体中文优先、其次英文；同一语言优先 WebVTT。
     * 字幕 CDN（googlevideo）在被墙网络下需走配置的出站代理。
     */
    public void downloadYoutubeSubtitleToResponse(String url, String title,
                                                  String userCookieContent,
                                                  HttpServletResponse response) {
        downloadYoutubeSubtitleToResponse(url, title, null, userCookieContent, response);
    }

    /**
     * YouTube 独立字幕下载：用 yt-dlp dumpInfo 拿到字幕轨道直链后直接 HTTP 下载返回。
     * 人工字幕优先于自动字幕，同语言优先 WebVTT。preferredLang 非空时下载指定语言。
     * 字幕 CDN（googlevideo）在被墙网络下需走配置的出站代理。
     */
    public void downloadYoutubeSubtitleToResponse(String url, String title, String preferredLang,
                                                  String userCookieContent,
                                                  HttpServletResponse response) {
        JsonNode info = ytDlp.dumpInfo(url, userCookieContent);
        YtDlpService.SubtitleTrack track = YtDlpService.chooseSubtitleTrack(info, preferredLang);
        if (track == null) {
            throw new BusinessException(preferredLang != null && !preferredLang.isBlank()
                    ? "该视频没有 " + preferredLang + " 语言的字幕轨道"
                    : "该视频没有可用字幕");
        }
        String body = fetchSubtitleBody(track.url());
        if (body == null || body.isBlank()) {
            throw new BusinessException("字幕下载失败，请稍后重试");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String filename = sanitizeTitle(title) + "." + track.lang() + "." + track.ext();

        response.setContentType(subtitleContentType(track.ext()));
        response.setContentLength(bytes.length);
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader("Cache-Control", "no-store");
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            log.warn("字幕响应写出失败: {}", e.getMessage());
            return;
        }
        log.info("字幕下载完成: {} ({}, {} bytes)", filename, track.lang(), bytes.length);
    }

    private String fetchSubtitleBody(String trackUrl) {
        boolean useProxy = trackUrl.contains("googlevideo") || trackUrl.contains("youtube.com");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(trackUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = subtitleClient(useProxy)
                    .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("字幕直链返回 HTTP {}", resp.statusCode());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("下载字幕直链失败: {}", e.getMessage());
            return null;
        }
    }

    private HttpClient subtitleClient(boolean useProxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10));
        if (useProxy) {
            String proxy = ytDlp.proxy();
            if (proxy != null && !proxy.isBlank()) {
                try {
                    URI uri = URI.create(proxy);
                    builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),
                            uri.getPort() > 0 ? uri.getPort() : 80)));
                } catch (Exception e) {
                    log.warn("字幕代理配置无效，回退直连: {}", proxy);
                }
            }
        }
        return builder.build();
    }

    private String subtitleContentType(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase()) {
            case "vtt" -> "text/vtt; charset=utf-8";
            case "srt" -> "application/x-subrip; charset=utf-8";
            case "ttml", "xml" -> "text/xml; charset=utf-8";
            case "json3", "json" -> "application/json; charset=utf-8";
            default -> "text/plain; charset=utf-8";
        };
    }

    /**
     * 以附件形式返回文本内容（镜像字幕等），按 UTF-8 编码
     */
    public void writeTextAttachment(String content, String filename, String contentType,
                                    HttpServletResponse response) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        response.setContentType(contentType);
        response.setContentLength(bytes.length);
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader("Cache-Control", "no-store");
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        }
        log.info("文本附件下载完成: {} ({} bytes)", filename, bytes.length);
    }

    /**
     * yt-dlp 下载视频到指定目录，返回生成的视频文件 Path。
     */
    private Path downloadVideoToFile(String url, String formatId, Path dir,
                                     String userCookieContent, String taskId,
                                     List<String> extraYtdlpArgs) throws InterruptedException, IOException {
        Path tempCookie = null;
        Path pattern = dir.resolve("video.%(ext)s");
        if (userCookieContent != null && !userCookieContent.isBlank()) {
            tempCookie = ytDlp.materializeCookieFile(userCookieContent);
        }
        List<String> cmd = ytDlp.buildDownloadCmd(url, formatId, pattern.toString(), tempCookie, extraYtdlpArgs);

        log.info("开始服务端下载: {} format={}", url, formatId);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        ytDlp.enhanceEnvironment(pb);
        Process process = pb.start();
        StringBuilder stderr = new StringBuilder();
        Thread outThread = drainProgress(process.getInputStream(), taskId);
        Thread errThread = drain(process.getErrorStream(), stderr);
        int code = process.waitFor();
        outThread.join(3000);
        errThread.join(3000);
        if (tempCookie != null) {
            try { Files.deleteIfExists(tempCookie); } catch (IOException ignored) {}
        }
        if (code != 0) {
            log.warn("yt-dlp 下载失败 (code {}): {}", code, stderr);
            progress.sendError(taskId, "下载失败，该格式可能暂不可用，请尝试其他清晰度");
            throw new BusinessException("下载失败，该格式可能暂不可用，请尝试其他清晰度");
        }

        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith("video."))
                    .filter(p -> !p.getFileName().toString().endsWith(".part"))
                    .max(Comparator.comparingLong(DownloadService::sizeOf))
                    .orElseThrow(() -> new BusinessException("下载失败：未生成文件"));
        }
    }

    private void streamFile(Path file, String title, HttpServletResponse response, String taskId) throws IOException {
        long size = Files.size(file);
        String ext = extOf(file.getFileName().toString());
        String filename = sanitizeTitle(title) + "." + ext;

        response.setContentType(contentType(ext));
        response.setContentLengthLong(size);
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader("Cache-Control", "no-store");

        try (OutputStream out = response.getOutputStream()) {
            Files.copy(file, out);
            out.flush();
        }
        progress.sendDone(taskId);
        log.info("下载完成: {} ({}), 发送给客户端 {}", filename, YtDlpService.humanSize(size), size);
    }

    /**
     * 直连流式下载（抖音无水印直链等）：带 UA/Referer 请求直链，边收边发给浏览器
     */
    public void downloadDirectToResponse(String directUrl, String referer, String title, HttpServletResponse response) {
        downloadDirectToResponse(directUrl, referer, title, response, null);
    }

    public void downloadDirectToResponse(String directUrl, String referer, String filename,
                                         HttpServletResponse response, String taskId) {
        // googlevideo/youtube.com 域名（镜像流直链）在被墙网络下需走配置的出站代理
        java.net.http.HttpClient client = subtitleClient(
                directUrl.contains("googlevideo") || directUrl.contains("youtube.com"));
        java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(directUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .timeout(java.time.Duration.ofMinutes(10))
                .GET();
        if (referer != null && !referer.isBlank()) {
            reqBuilder.header("Referer", referer);
        }
        java.net.http.HttpResponse<java.io.InputStream> resp;
        try {
            resp = client.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new BusinessException("获取视频流失败：" + e.getMessage());
        }
        if (resp.statusCode() != 200) {
            throw new BusinessException("视频源返回状态码 " + resp.statusCode() + "，请稍后重试");
        }

        String contentType = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
        if (contentType.contains("text/html")) {
            throw new BusinessException("视频链接已失效，请重新解析");
        }
        long size = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        // filename 已含媒体扩展名时直接使用；否则按 Content-Type 推导扩展名（兼容只传标题的调用方）
        String finalFilename = MEDIA_EXT_PATTERN.matcher(filename).find()
                ? filename
                : sanitizeTitle(filename) + "." + extFromContentType(contentType);
        response.setContentType(contentType.startsWith("video/") || contentType.startsWith("audio/")
                ? contentType : "application/octet-stream");
        if (size > 0) {
            response.setContentLengthLong(size);
        }
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(finalFilename, StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader("Cache-Control", "no-store");
        try (java.io.InputStream in = resp.body(); OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long received = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                received += n;
                progress.sendProgress(taskId, received, size, 0);
            }
            out.flush();
            progress.sendDone(taskId);
        } catch (IOException e) {
            log.warn("客户端中断下载: {}", e.getMessage());
        }
    }

    private Thread drain(java.io.InputStream is, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
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

    /**
     * 逐行读 stdout，识别进度行并通过 WebSocket 推送；其余行忽略
     */
    private Thread drainProgress(java.io.InputStream is, String taskId) {
        Thread t = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseProgressLine(line, taskId);
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void parseProgressLine(String line, String taskId) {
        if (taskId == null) {
            return;
        }
        Matcher m = FVD_PROG.matcher(line.trim());
        if (m.matches()) {
            long downloaded = parseLong(m.group(1));
            long total = parseLong(m.group(2));
            long speed = parseLong(m.group(3));
            // HLS 分片下载无总字节数：按已完成分片比例反推总量，驱动前端百分比（封顶 99%）
            if (total <= 0) {
                long fragIndex = parseLong(m.group(4));
                long fragCount = parseLong(m.group(5));
                if (downloaded > 0 && fragIndex > 0 && fragCount > 0) {
                    double ratio = Math.min((double) fragIndex / fragCount, 0.99);
                    total = Math.round(downloaded / ratio);
                }
            }
            progress.sendProgress(taskId, downloaded, total, speed);
            return;
        }
        m = ARIA2_PROG.matcher(line);
        if (m.find()) {
            progress.sendProgress(taskId, parseSize(m.group(1)), parseSize(m.group(2)), parseSize(m.group(3)));
        }
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "mp4";
    }

    private String contentType(String ext) {
        return switch (ext.toLowerCase()) {
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "mov" -> "video/quicktime";
            case "m4a" -> "audio/mp4";
            case "mp3" -> "audio/mpeg";
            case "opus" -> "audio/opus";
            default -> "application/octet-stream";
        };
    }

    /**
     * 按 Content-Type 推导媒体扩展名（兼容只传标题、不带扩展名的直链下载调用方）
     */
    private String extFromContentType(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("mpeg") || ct.contains("mp3")) {
            return "mp3";
        }
        if (ct.contains("flac")) {
            return "flac";
        }
        if (ct.contains("webm")) {
            return "webm";
        }
        if (ct.contains("ogg")) {
            return "ogg";
        }
        if (ct.contains("wav")) {
            return "wav";
        }
        if (ct.contains("aac")) {
            return "aac";
        }
        if (ct.contains("mp4") || ct.contains("m4a")) {
            return ct.contains("video") ? "mp4" : "m4a";
        }
        return "mp4";
    }

    private String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "video";
        }
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        return cleaned.isEmpty() ? "video" : cleaned;
    }

    private void cleanup(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir, e);
        }
    }
}
