package com.fvd.video.interfaces;

import com.fvd.auth.domain.User;
import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.application.DownloadService;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import com.fvd.video.infrastructure.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VideoController {

    private final YtDlpService ytDlp;
    private final DouyinParser douyinParser;
    private final InstagramParser instagramParser;
    private final CctvParser cctvParser;
    private final PornhubParser pornhubParser;
    private final MissavParser missavParser;
    private final TubiParser tubiParser;
    private final CgtnParser cgtnParser;
    private final BbcParser bbcParser;
    private final HlsClient hlsClient;
    private final CctvNodeDecryptSidecar cctvNodeDecryptSidecar;
    private final DownloadService downloadService;
    private final CookieService cookieService;

    /**
     * 解析视频信息。抖音/Instagram 必须登录并配置该平台 cookies。
     */
    @PostMapping("/parse")
    public ApiResponse<VideoInfo> parse(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody ParseReq req) {
        String url = validateUrl(req.getUrl());
        if (douyinParser.supports(url)) {
            String cookies = cookieService.requireContent(user, Platform.DOUYIN);
            try {
                return ApiResponse.ok(douyinParser.parse(url, cookies));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.DOUYIN, e.getMessage());
                throw e;
            }
        }
        if (instagramParser.supports(url)) {
            String cookies = cookieService.requireContent(user, Platform.INSTAGRAM);
            try {
                return ApiResponse.ok(instagramParser.parse(url, user.getId(), cookies));
            } catch (InstagramParser.LoginRequiredException e) {
                // 门控说明 cookies 无效，标记后直接返回明确提示（回退 yt-dlp 同样拿不到）
                cookieService.markInvalid(user, Platform.INSTAGRAM, e.getMessage());
                throw e;
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.INSTAGRAM, e.getMessage());
                // 图文解析失败时，回退 yt-dlp（普通视频帖）
                return ApiResponse.ok(ytDlp.parse(url, cookies));
            }
        }
        if (pornhubParser.supports(url)) {
            return ApiResponse.ok(pornhubParser.parse(url, null));
        }
        if (missavParser.supports(url)) {
            return ApiResponse.ok(missavParser.parse(url));
        }
        // CCTV：纯 Java 解析，多清晰度探测（可选 cookies，用于 VIP 内容）
        if (cctvParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.CCTV);
            try {
                return ApiResponse.ok(cctvParser.parse(url, cookies));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.CCTV, e.getMessage());
                throw e;
            }
        }
        // CGTN：纯 Java 解析，从 data-video 属性提取 m3u8
        if (cgtnParser.supports(url)) {
            return ApiResponse.ok(cgtnParser.parse(url, null));
        }
        // BBC：纯 Java 解析（__NEXT_DATA__ → playlist.json → mediaselector API）
        if (bbcParser.supports(url)) {
            return ApiResponse.ok(bbcParser.parse(url, null));
        }
        // Tubi：纯 Java 解析（匿名设备认证链 + CMS v3 API），无需 cookies
        if (tubiParser.supports(url)) {
            return ApiResponse.ok(tubiParser.parse(url));
        }
        // YouTube/Twitter/TikTok/Bilibili 等：登录用户有上传 cookies 就带上，没有则匿名（YouTube 回退全局）
        Platform platform = Platform.from(url);
        String cookies = cookieService.findContent(user, platform);
        try {
            return ApiResponse.ok(ytDlp.parse(url, cookies));
        } catch (BusinessException e) {
            cookieService.markInvalidIfAuth(user, platform, e.getMessage());
            throw e;
        }
    }

    /**
     * 获取视频直链（直链下载模式）
     */
    @PostMapping("/direct-url")
    public ApiResponse<Map<String, Object>> directUrl(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody DirectUrlReq req) {
        String url = validateUrl(req.getUrl());
        if (douyinParser.supports(url)) {
            String cookies = cookieService.requireContent(user, Platform.DOUYIN);
            String direct = douyinParser.tryDirectUrl(url, cookies);
            if (direct != null) {
                return ApiResponse.ok(Map.of("direct_url", direct, "ext", "mp4"));
            }
            try {
                return ApiResponse.ok(Map.of("direct_url", ytDlp.directUrl(url, req.getFormatId(), cookies)));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.DOUYIN, e.getMessage());
                throw e;
            }
        }
        if (instagramParser.supports(url)) {
            String cookies = cookieService.requireContent(user, Platform.INSTAGRAM);
            try {
                return ApiResponse.ok(Map.of("direct_url", ytDlp.directUrl(url, req.getFormatId(), cookies)));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.INSTAGRAM, e.getMessage());
                throw e;
            }
        }
        // MissAV 使用跨站 HLS 且 CDN 校验浏览器请求头，只支持服务端下载。
        if (missavParser.supports(url)) {
            throw new BusinessException("MissAV 视频为 HLS 流，不支持浏览器直链，请使用服务端下载");
        }
        // CGTN：HLS 流，不支持浏览器直链，走服务端下载
        if (cgtnParser.supports(url)) {
            throw new BusinessException("CGTN 视频为 HLS 流，不支持浏览器直链，请使用服务端下载");
        }
        // BBC：HLS 流带签名 token，不支持浏览器直链，走服务端下载
        if (bbcParser.supports(url)) {
            throw new BusinessException("BBC 视频为 HLS 流，不支持浏览器直链，请使用服务端下载");
        }
        // Tubi：HLS 带 token 流，不支持浏览器直链，走服务端下载
        if (tubiParser.supports(url)) {
            throw new BusinessException("Tubi 视频为 HLS 流，不支持浏览器直链，请使用服务端下载");
        }
        Platform platform = Platform.from(url);
        String cookies = cookieService.findContent(user, platform);
        try {
            return ApiResponse.ok(Map.of("direct_url", ytDlp.directUrl(url, req.getFormatId(), cookies)));
        } catch (BusinessException e) {
            cookieService.markInvalidIfAuth(user, platform, e.getMessage());
            throw e;
        }
    }

    /**
     * 服务端代理下载
     */
    @PostMapping("/download")
    public void download(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody DownloadReq req, HttpServletResponse response) {
        String url = validateUrl(req.getUrl());
        String title = req.getTitle();
        // 抖音：优先无水印直链流式转发；不可用则回退 yt-dlp
        if (douyinParser.supports(url)) {
            String cookies = cookieService.requireContent(user, Platform.DOUYIN);
            String direct = douyinParser.tryDirectUrl(url, cookies);
            if (direct != null) {
                downloadService.downloadDirectToResponse(direct, "https://www.douyin.com/",
                        title != null ? title : "douyin-video", response, req.getTaskId());
                return;
            }
            try {
                downloadService.downloadToResponse(url, req.getFormatId(), title, response, cookies, req.getTaskId());
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.DOUYIN, e.getMessage());
                throw e;
            }
            return;
        }
        // Instagram 图文：服务端代理下载/打包
        if (instagramParser.supports(url)) {
            String cookies = cookieService.requireContent(user, Platform.INSTAGRAM);
            if ("images".equals(req.getFormatId())) {
                try {
                    instagramParser.download(url, title, response, user.getId(), cookies);
                } catch (InstagramParser.LoginRequiredException e) {
                    cookieService.markInvalid(user, Platform.INSTAGRAM, e.getMessage());
                    throw e;
                } catch (BusinessException e) {
                    cookieService.markInvalidIfAuth(user, Platform.INSTAGRAM, e.getMessage());
                    throw e;
                }
                return;
            }
            try {
                downloadService.downloadToResponse(url, req.getFormatId(), title, response, cookies, req.getTaskId());
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.INSTAGRAM, e.getMessage());
                throw e;
            }
            return;
        }
        // CCTV：用 HlsClient 原生下载 m3u8 分片 → 合并 MP4（不经过 yt-dlp）
        if (cctvParser.supports(url)) {
            String cookieContent = cookieService.findContent(user, Platform.CCTV);
            String streamUrl = cctvParser.resolveDownloadUrl(url, req.getFormatId());
            CctvParser.CctvDownloadParams params = cctvParser.resolveDownloadParams(url, req.getFormatId());
            if (streamUrl == null || params.masterUrl() == null) {
                // 缓存丢失（服务器重启后），重新解析填充缓存
                cctvParser.parse(url, cookieContent);
                streamUrl = cctvParser.resolveDownloadUrl(url, req.getFormatId());
                params = cctvParser.resolveDownloadParams(url, req.getFormatId());
            }
            String cookieHeader = cctvParser.getCookieHeader(cookieContent);
            if (streamUrl != null && params.masterUrl() != null) {
                // h5e 端点被 WASM 加密，走 Node.js 批量解密 sidecar（Playwright 页面 WASM，约 20x 速度）
                try {
                    cctvNodeDecryptSidecar.decryptAndDownload(streamUrl,
                            title != null ? title : "cctv-video", response, req.getTaskId());
                } catch (BusinessException e) {
                    cookieService.markInvalidIfAuth(user, Platform.CCTV, e.getMessage());
                    throw e;
                }
                return;
            }
            if (streamUrl != null) {
                // 未加密端点回退：HlsClient + ffmpeg 直接合并
                try {
                    hlsClient.downloadToResponse(streamUrl, title, cookieHeader, response, req.getTaskId());
                } catch (BusinessException e) {
                    cookieService.markInvalidIfAuth(user, Platform.CCTV, e.getMessage());
                    throw e;
                }
                return;
            }
        }
        // MissAV：从页面还原 surrit HLS 主清单，并用 yt-dlp 浏览器模拟请求清单和分片。
        if (missavParser.supports(url)) {
            MissavParser.DownloadTarget target = missavParser.resolveDownload(url);
            downloadService.downloadToResponse(target.masterUrl(), req.getFormatId(),
                    title != null ? title : "missav-video", response, null, req.getTaskId(),
                    target.ytDlpArgs());
            return;
        }
        // CGTN：HLS m3u8 被墙，走代理下载
        if (cgtnParser.supports(url)) {
            cgtnParser.download(url, req.getFormatId(),
                    title != null ? title : "cgtn-video", response, req.getTaskId());
            return;
        }
        // BBC：HLS 带签名 token，走 HlsClient 服务端下载
        if (bbcParser.supports(url)) {
            bbcParser.download(url, req.getFormatId(),
                    title != null ? title : "bbc-video", response, req.getTaskId());
            return;
        }
        // Tubi：ffmpeg 直连 m3u8 经代理拉分片会出现 byte-range 数据错位，
        // 改走 yt-dlp（原生 HLS 下载器正确处理 EXT-X-BYTERANGE + 音频分离组），-N 128 并发加速
        if (tubiParser.supports(url)) {
            String streamUrl = tubiParser.resolveDownloadUrl(url, req.getFormatId());
            if (streamUrl == null) {
                // 缓存丢失（服务器重启后），重新解析填充缓存
                tubiParser.parse(url);
                streamUrl = tubiParser.resolveDownloadUrl(url, req.getFormatId());
            }
            if (streamUrl == null) {
                throw new BusinessException("请先解析视频后再下载");
            }
            downloadService.downloadToResponse(streamUrl, null, title != null ? title : "tubi-video",
                    response, null, req.getTaskId(), List.of("-N", "128", "--socket-timeout", "90"));
            return;
        }
        Platform platform = Platform.from(url);
        String cookies = cookieService.findContent(user, platform);
        try {
            downloadService.downloadToResponse(url, req.getFormatId(), title, response, cookies, req.getTaskId());
        } catch (BusinessException e) {
            cookieService.markInvalidIfAuth(user, platform, e.getMessage());
            throw e;
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
    public static class ParseReq {
        private String url;
    }

    @Data
    public static class DirectUrlReq {
        private String url;
        private String formatId;
    }

    @Data
    public static class DownloadReq {
        private String url;
        private String formatId;
        private String title;
        /**
         * 下载进度推送标识：前端建立 ws 连接后传入，后端据此推送进度
         */
        private String taskId;
    }
}
