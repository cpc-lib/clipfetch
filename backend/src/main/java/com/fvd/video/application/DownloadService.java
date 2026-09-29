package com.fvd.video.application;

import com.fvd.shared.web.BusinessException;
import com.fvd.video.infrastructure.DownloadProgressHandler;
import com.fvd.video.infrastructure.YtDlpService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 服务端代理下载：yt-dlp 下载到临时目录，再流式返回给浏览器，最后清理临时文件
 */
@Slf4j
@Service
public class DownloadService {

    // yt-dlp --progress-template 输出：FVDPROG|<downloaded>|<total>|<speed>（未知为 NA）
    private static final Pattern FVD_PROG = Pattern.compile("^FVDPROG\\|(\\S+)\\|(\\S+)\\|(\\S+)$");
    // aria2c 外部下载器输出：[#2085b8 1.5MiB/6.6MiB(13%) CN:16 DL:0.9MiB ETA:5s]
    private static final Pattern ARIA2_PROG = Pattern.compile(
            "\\[#\\w+\\s+([\\d.]+\\w+)/([\\d.]+\\w+)\\(\\d+%\\).*?DL:([\\d.]+\\w+)");
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
        Path tempCookie = null;
        try {
            dir = Files.createDirectories(Path.of(downloadsDir, UUID.randomUUID().toString()));
            Path pattern = dir.resolve("video.%(ext)s");
            if (userCookieContent != null && !userCookieContent.isBlank()) {
                tempCookie = ytDlp.materializeCookieFile(userCookieContent);
            }
            List<String> cmd = ytDlp.buildDownloadCmd(url, formatId, pattern.toString(), tempCookie, extraYtdlpArgs);

            log.info("开始服务端下载: {} format={}", url, formatId);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            // 下载阶段同样需要 deno（PO token）、aria2c（多连接）、ffmpeg（合并）
            ytDlp.enhanceEnvironment(pb);
            Process process = pb.start();
            StringBuilder stderr = new StringBuilder();
            // 必须同时排空 stdout 和 stderr：进度输出写满管道缓冲区会把子进程阻塞挂死
            Thread outThread = drainProgress(process.getInputStream(), taskId);
            Thread errThread = drain(process.getErrorStream(), stderr);
            int code = process.waitFor();
            outThread.join(3000);
            errThread.join(3000);
            if (code != 0) {
                log.warn("yt-dlp 下载失败 (code {}): {}", code, stderr);
                progress.sendError(taskId, "下载失败，该格式可能暂不可用，请尝试其他清晰度");
                throw new BusinessException("下载失败，该格式可能暂不可用，请尝试其他清晰度");
            }

            Path file;
            try (Stream<Path> list = Files.list(dir)) {
                file = list.filter(p -> p.getFileName().toString().startsWith("video."))
                        .filter(p -> !p.getFileName().toString().endsWith(".part"))
                        .max(Comparator.comparingLong(DownloadService::sizeOf))
                        .orElseThrow(() -> new BusinessException("下载失败：未生成文件"));
            }

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
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("下载被中断");
        } catch (IOException e) {
            throw new BusinessException("下载失败：" + e.getMessage());
        } finally {
            cleanup(dir);
            if (tempCookie != null) {
                try {
                    Files.deleteIfExists(tempCookie);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 直连流式下载（抖音无水印直链等）：带 UA/Referer 请求直链，边收边发给浏览器
     */
    public void downloadDirectToResponse(String directUrl, String referer, String title, HttpServletResponse response) {
        downloadDirectToResponse(directUrl, referer, title, response, null);
    }

    public void downloadDirectToResponse(String directUrl, String referer, String title,
                                         HttpServletResponse response, String taskId) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
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

        String contentType = resp.headers().firstValue("Content-Type").orElse("video/mp4");
        if (contentType.contains("text/html")) {
            throw new BusinessException("视频链接已失效，请重新解析");
        }
        long size = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        String filename = sanitizeTitle(title) + ".mp4";
        response.setContentType(contentType.startsWith("video/") || contentType.startsWith("audio/")
                ? contentType : "video/mp4");
        if (size > 0) {
            response.setContentLengthLong(size);
        }
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
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
            progress.sendProgress(taskId, parseLong(m.group(1)), parseLong(m.group(2)), parseLong(m.group(3)));
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
