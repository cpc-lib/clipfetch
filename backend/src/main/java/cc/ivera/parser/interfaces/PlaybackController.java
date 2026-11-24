package cc.ivera.parser.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.parser.application.PlaybackMergeService;
import cc.ivera.parser.application.PlaybackProgressService;
import cc.ivera.parser.application.PlaybackSourceService;
import cc.ivera.parser.application.PlaybackTicket;
import cc.ivera.parser.application.PlaybackTicketStore;
import cc.ivera.parser.domain.PlaybackProgress;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.infrastructure.playback.HlsPlaylistRewriter;
import cc.ivera.parser.infrastructure.playback.PlaybackProxyClient;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 在线播放：票据申请需要登录；流代理端点免登录，凭短时票据校验。
 * 浏览器原生媒体请求无法携带 Authorization 头，因此不能把 JWT 放进 URL。
 */
@Slf4j
@RestController
@RequestMapping("/api/play")
public class PlaybackController {

    private final PlaybackSourceService sourceService;
    private final PlaybackTicketStore ticketStore;
    private final PlaybackProxyClient proxyClient;
    private final HlsPlaylistRewriter hlsRewriter;
    private final PlaybackProgressService progressService;
    private final PlaybackMergeService mergeService;
    private final boolean enabled;
    private final int bufferBytes;

    public PlaybackController(PlaybackSourceService sourceService,
                              PlaybackTicketStore ticketStore,
                              PlaybackProxyClient proxyClient,
                              HlsPlaylistRewriter hlsRewriter,
                              PlaybackProgressService progressService,
                              PlaybackMergeService mergeService,
                              @Value("${app.play.enabled:true}") boolean enabled,
                              @Value("${app.play.buffer-bytes:65536}") int bufferBytes) {
        this.sourceService = sourceService;
        this.ticketStore = ticketStore;
        this.proxyClient = proxyClient;
        this.hlsRewriter = hlsRewriter;
        this.progressService = progressService;
        this.mergeService = mergeService;
        this.enabled = enabled;
        this.bufferBytes = bufferBytes;
    }

    @PostMapping("/ticket")
    public ApiResponse<TicketResponse> create(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody TicketReq req) {
        if (!enabled) {
            throw new BusinessException("在线播放功能未开启");
        }
        // 每次创建票据顺带清扫过期合并缓存，避免进程异常退出后孤儿文件长期残留
        mergeService.cleanupExpired();
        String url = validateUrl(req.getUrl());
        if (req.getFormatId() == null || req.getFormatId().isBlank()) {
            throw new BusinessException("请选择清晰度后再播放");
        }
        PlaybackSourceService.Resolved resolved =
                sourceService.resolve(url, req.getFormatId(), req.getTitle(), user);
        PlaybackTicket ticket = ticketStore.create(user != null ? user.getId() : 0L,
                url, req.getFormatId(), resolved.mode(), resolved.upstreamUri(),
                resolved.referer(), resolved.cookie(), resolved.userAgent(), resolved.useProxy(),
                resolved.mime(), resolved.audioOnly());
        if ("merge".equals(resolved.mode())) {
            try {
                if (Platform.from(url) == Platform.CCTV) {
                    // CCTV 仅 h5e 加密流：后台 Node 解密落盘
                    mergeService.submitCctvDecrypt(ticket, resolved.upstreamUri().toString());
                } else {
                    mergeService.submit(ticket, url, req.getFormatId(), resolved.cookie());
                }
            } catch (RuntimeException e) {
                ticketStore.remove(ticket);
                throw e;
            }
        }
        PlaybackProgress progress = progressService.find(ticket.getUserId(), url);
        log.info("播放票据创建: userId={}, mode={}", ticket.getUserId(), ticket.getMode());
        return ApiResponse.ok(new TicketResponse(ticket.getToken(), ticket.getMode(),
                ticket.getMime(), resolved.title(), ticket.isAudioOnly(),
                ticket.getHardExpiresAt().toString(), progressService.resumeSeconds(progress),
                progressService.isCompleted(progress), prepareInfo(ticket)));
    }

    /** merge 准备进度；非 merge 票据返回 null */
    @GetMapping("/prepare/{ticket}")
    public ResponseEntity<?> prepare(@PathVariable String ticket) {
        PlaybackTicket t = ticketStore.find(ticket);
        if (t == null) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                    .body("播放地址已过期，请重新播放");
        }
        if (!"merge".equals(t.getMode())) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("该播放不需要准备");
        }
        return ResponseEntity.ok(ApiResponse.ok(prepareInfo(t)));
    }

    private PrepareInfo prepareInfo(PlaybackTicket ticket) {
        if (!"merge".equals(ticket.getMode())) {
            return null;
        }
        return new PrepareInfo(ticket.getMergeStatus(), ticket.getMergeProgress().get(),
                ticket.getMergeError());
    }

    @GetMapping("/progress")
    public ApiResponse<ProgressResponse> getProgress(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestParam String url) {
        PlaybackProgress progress = progressService.find(user.getId(), url);
        return ApiResponse.ok(progressResponse(progress));
    }

    @PostMapping("/progress")
    public ApiResponse<Boolean> saveProgress(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody ProgressReq req) {
        PlaybackProgressService.SaveCommand command = new PlaybackProgressService.SaveCommand(
                req.getUrl(), req.getPositionSeconds(), req.getDurationSeconds(),
                req.getFormatId(), req.getMode(), req.getTitle(), req.getCompleted());
        progressService.save(user.getId(), command);
        return ApiResponse.ok(true);
    }

    private ProgressResponse progressResponse(PlaybackProgress progress) {
        if (progress == null) {
            return null;
        }
        return new ProgressResponse(progress.getPositionSeconds(), progress.getDurationSeconds(),
                progressService.isCompleted(progress), progress.getUpdatedAt().toString());
    }

    /** 渐进式单文件代理：转发 Range，透传 200/206/416 */
    @RequestMapping(value = "/stream/{ticket}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void stream(@PathVariable String ticket, HttpServletRequest request,
                       HttpServletResponse response) {
        PlaybackTicket t = ticketStore.find(ticket);
        if (t == null) {
            writeError(response, 404, "播放地址已过期，请重新播放");
            return;
        }
        if ("merge".equals(t.getMode())) {
            serveMergeFile(t, request, response);
            return;
        }
        String range = request.getHeader("Range");
        if (range != null && range.contains(",")) {
            writeError(response, 416, "暂不支持多段 Range 请求");
            return;
        }
        boolean head = "HEAD".equalsIgnoreCase(request.getMethod());
        try (PlaybackProxyClient.Upstream up = proxyClient.open(t, t.getUpstreamUri(), range)) {
            if (!head) {
                ticketStore.registerStream(t, up);
            }
            try {
                writeUpstream(up, response, head, t.getMime());
            } finally {
                if (!head) {
                    ticketStore.unregisterStream(t, up);
                }
            }
        } catch (BusinessException e) {
            writeError(response, 502, e.getMessage());
        } catch (IOException e) {
            log.debug("客户端中断播放流: {}", e.getMessage());
        }
    }

    /** HLS 入口：拉取上游 playlist，改写为同源子 token 地址 */
    @GetMapping("/hls/{ticket}/master.m3u8")
    public void hlsMaster(@PathVariable String ticket, HttpServletResponse response) {
        PlaybackTicket t = ticketStore.find(ticket);
        if (t == null) {
            writeError(response, 404, "播放地址已过期，请重新播放");
            return;
        }
        try {
            PlaybackProxyClient.PlaylistText playlist =
                    proxyClient.fetchPlaylist(t, t.getUpstreamUri());
            writePlaylist(response, hlsRewriter.rewrite(
                    playlist.text(), playlist.finalUri(), t, ticketStore));
        } catch (BusinessException e) {
            writeError(response, 502, e.getMessage());
        } catch (IOException e) {
            log.debug("客户端中断 HLS 请求: {}", e.getMessage());
        }
    }

    /** CCTV 浏览器解密脚本：复用 HLS 票据校验，脚本与 WASM 均来自后端 classpath。 */
    @GetMapping("/hls/{ticket}/cctv.worker.js")
    public void cctvWorker(@PathVariable String ticket, HttpServletResponse response) {
        PlaybackTicket t = ticketStore.find(ticket);
        if (t == null || !"hls-decrypt".equals(t.getMode())) {
            writeError(response, 404, "解密组件地址已过期，请重新播放");
            return;
        }
        ClassPathResource worker = new ClassPathResource("cctv/cctv.worker.js");
        response.setStatus(200);
        response.setContentType("text/javascript; charset=utf-8");
        response.setHeader("Cache-Control", "private, max-age=1800");
        try (InputStream in = worker.getInputStream(); OutputStream out = response.getOutputStream()) {
            response.setContentLengthLong(worker.contentLength());
            in.transferTo(out);
            out.flush();
        } catch (IOException e) {
            log.debug("客户端中断 CCTV 解密脚本请求: {}", e.getMessage());
        }
    }

    /** CCTV WASM 固定配置：仅代理固定上游地址，避免 Worker 跨域读取失败。 */
    @GetMapping("/hls/{ticket}/H5player.json")
    public void cctvPlayerConfig(@PathVariable String ticket, HttpServletResponse response) {
        PlaybackTicket t = ticketStore.find(ticket);
        if (t == null || !"hls-decrypt".equals(t.getMode())) {
            writeError(response, 404, "解密配置地址已过期，请重新播放");
            return;
        }
        URI configUri = URI.create("https://tv.cctv.com/Library/H5player.json");
        try (PlaybackProxyClient.Upstream up = proxyClient.open(t, configUri, null)) {
            writeUpstream(up, response, false, "application/json");
        } catch (BusinessException e) {
            writeError(response, 502, e.getMessage());
        } catch (IOException e) {
            log.debug("客户端中断 CCTV 解密配置请求: {}", e.getMessage());
        }
    }

    /** HLS 子资源：子 playlist 再次改写，分片/密钥/init map 透传 */
    @GetMapping("/hls/{ticket}/{childToken}")
    public void hlsChild(@PathVariable String ticket, @PathVariable String childToken,
                         HttpServletRequest request, HttpServletResponse response) {
        PlaybackTicket t = ticketStore.find(ticket);
        if (t == null) {
            writeError(response, 404, "播放地址已过期，请重新播放");
            return;
        }
        PlaybackTicket.Child child = ticketStore.findChild(t, childToken);
        if (child == null) {
            writeError(response, 404, "播放子资源不存在或已过期");
            return;
        }
        boolean playlist = isPlaylist(child);
        if (playlist) {
            try {
                PlaybackProxyClient.PlaylistText text =
                        proxyClient.fetchPlaylist(t, child.uri());
                writePlaylist(response, hlsRewriter.rewrite(
                        text.text(), text.finalUri(), t, ticketStore));
            } catch (BusinessException e) {
                writeError(response, 502, e.getMessage());
            } catch (IOException e) {
                log.debug("客户端中断 HLS 子 playlist 请求: {}", e.getMessage());
            }
            return;
        }
        String range = request.getHeader("Range");
        if (range != null && range.contains(",")) {
            writeError(response, 416, "暂不支持多段 Range 请求");
            return;
        }
        try (PlaybackProxyClient.Upstream up = proxyClient.open(t, child.uri(), range)) {
            ticketStore.registerStream(t, up);
            try {
                writeUpstream(up, response, false, child.contentTypeHint());
            } finally {
                ticketStore.unregisterStream(t, up);
            }
        } catch (BusinessException e) {
            writeError(response, 502, e.getMessage());
        } catch (IOException e) {
            log.debug("客户端中断 HLS 分片请求: {}", e.getMessage());
        }
    }

    private boolean isPlaylist(PlaybackTicket.Child child) {
        String hint = child.contentTypeHint();
        if (hint != null && hint.toLowerCase(Locale.ROOT).contains("mpegurl")) {
            return true;
        }
        String path = child.uri().getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    private void writeUpstream(PlaybackProxyClient.Upstream up, HttpServletResponse response,
                               boolean head, String fallbackMime) throws IOException {
        int status = up.status();
        if (status == 416) {
            response.setStatus(416);
            up.headers().firstValue("Content-Range")
                    .ifPresent(v -> response.setHeader("Content-Range", v));
            return;
        }
        if (status < 200 || status >= 300) {
            writeError(response, 502, upstreamError(status));
            return;
        }
        String contentType = up.headers().firstValue("Content-Type").orElse(fallbackMime);
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            writeError(response, 502, "播放链接已失效，请重新播放");
            return;
        }
        response.setStatus(status);
        if (contentType != null) {
            response.setContentType(contentType);
        }
        up.headers().firstValue("Content-Length")
                .ifPresent(v -> response.setHeader("Content-Length", v));
        up.headers().firstValue("Content-Range")
                .ifPresent(v -> response.setHeader("Content-Range", v));
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "no-store");
        if (head) {
            return;
        }
        try (InputStream in = up.body(); OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[bufferBytes];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private void writePlaylist(HttpServletResponse response, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        response.setStatus(200);
        response.setContentType("application/vnd.apple.mpegurl");
        response.setContentLength(bytes.length);
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    private String upstreamError(int status) {
        if (status == 403 || status == 404 || status == 410) {
            return "播放地址已失效，请重新播放";
        }
        if (status >= 500) {
            return "视频源暂时不可用，请稍后重试";
        }
        return "获取播放流失败（上游状态码 " + status + "）";
    }

    /** merge 票据：READY 后按 Range 读取本地合并文件，准备中/失败返回 409 */
    private void serveMergeFile(PlaybackTicket ticket, HttpServletRequest request,
                                HttpServletResponse response) {
        if ("FAILED".equals(ticket.getMergeStatus())) {
            writeError(response, 409, ticket.getMergeError() == null
                    ? "视频准备失败，请重新播放" : ticket.getMergeError());
            return;
        }
        Path file = ticket.getLocalFile();
        if (!"READY".equals(ticket.getMergeStatus()) || file == null) {
            writeError(response, 409, "视频正在准备中，请稍候");
            return;
        }
        long total;
        try {
            total = Files.size(file);
        } catch (IOException e) {
            writeError(response, 404, "播放文件已失效，请重新播放");
            return;
        }
        String rangeHeader = request.getHeader("Range");
        boolean ranged = rangeHeader != null;
        long start = 0;
        long end = Math.max(0, total - 1);
        if (ranged) {
            long[] range = parseRange(rangeHeader, total);
            if (range == null) {
                response.setStatus(416);
                response.setHeader("Content-Range", "bytes */" + total);
                return;
            }
            start = range[0];
            end = range[1];
        }
        response.setStatus(ranged ? 206 : 200);
        response.setContentType("video/mp4");
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Length", String.valueOf(end - start + (total > 0 ? 1 : 0)));
        if (ranged) {
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
        }
        if ("HEAD".equalsIgnoreCase(request.getMethod()) || total == 0) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[bufferBytes];
            long remaining = end - start + 1;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        } catch (IOException e) {
            log.debug("客户端中断本地播放: {}", e.getMessage());
        }
    }

    /** 解析单段 Range；非法或越界返回 null */
    private long[] parseRange(String header, long total) {
        if (!header.startsWith("bytes=") || header.contains(",")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startText = spec.substring(0, dash).trim();
            String endText = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= total || end < start) {
                return null;
            }
            return new long[]{start, Math.min(end, total - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("text/plain; charset=utf-8");
        try {
            response.getWriter().write(message);
            response.getWriter().flush();
        } catch (IOException ignored) {
        }
    }

    private String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException("请输入视频链接");
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException("请输入以 http(s):// 开头的视频链接");
        }
        return url;
    }

    @Data
    public static class TicketReq {
        private String url;
        private String formatId;
        private String title;
    }

    @Data
    public static class ProgressReq {
        private String url;
        private Integer positionSeconds;
        private Integer durationSeconds;
        private String formatId;
        private String mode;
        private String title;
        private Boolean completed;
    }

    public record TicketResponse(String ticket, String mode, String mime, String title,
                                 boolean audioOnly, String expiresAt,
                                 int resumeSeconds, boolean resumeCompleted,
                                 PrepareInfo prepare) {
    }

    public record PrepareInfo(String status, int progress, String error) {
    }

    public record ProgressResponse(int positionSeconds, Integer durationSeconds,
                                   boolean completed, String updatedAt) {
    }
}
