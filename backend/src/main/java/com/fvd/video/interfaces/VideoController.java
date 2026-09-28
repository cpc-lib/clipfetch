package com.fvd.video.interfaces;

import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.auth.domain.User;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.infrastructure.DouyinParser;
import com.fvd.video.application.DownloadService;
import com.fvd.video.infrastructure.InstagramParser;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import com.fvd.video.infrastructure.YtDlpService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VideoController {

    private final YtDlpService ytDlp;
    private final DouyinParser douyinParser;
    private final InstagramParser instagramParser;
    private final DownloadService downloadService;
    private final CookieService cookieService;

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
        /** 下载进度推送标识：前端建立 ws 连接后传入，后端据此推送进度 */
        private String taskId;
    }

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
}
