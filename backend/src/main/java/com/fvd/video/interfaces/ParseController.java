package com.fvd.video.interfaces;

import com.fvd.auth.domain.User;
import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.application.ParserRegistry;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import com.fvd.video.application.parser.video.*;
import com.fvd.video.application.parser.music.*;
import com.fvd.video.infrastructure.service.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ParseController {

    private final YtDlpService ytDlp;
    private final ParserRegistry parserRegistry;
    private final DouyinParser douyinParser;
    private final InstagramParser instagramParser;
    private final XvideosParser xvideosParser;
    private final MissavParser missavParser;
    private final TubiParser tubiParser;
    private final CgtnParser cgtnParser;
    private final BbcParser bbcParser;
    private final AmasianTvParser amasianTvParser;
    private final VipParser vipParser;
    private final TencentParser tencentParser;
    private final RednoteParser rednoteParser;
    private final WeiboParser weiboParser;
    private final NetMirrorParser netMirrorParser;
    private final QQMusicParser qqMusicParser;
    private final CookieService cookieService;

    /**
     * 解析视频信息。抖音必须登录并配置 cookies；Instagram cookies 可选（匿名走后端代理，被门控时仍需上传）。
     */
    @PostMapping("/parse")
    public ApiResponse<VideoInfo> parse(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody ParseReq req) {
        String url = validateUrl(req.getUrl());
        return ApiResponse.ok(parserRegistry.parse(url, user));
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
            String cookies = cookieService.findContent(user, Platform.INSTAGRAM);
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
        // 小红书：CDN 直链带时效签名且校验 Referer，只支持服务端下载
        if (rednoteParser.supports(url)) {
            throw new BusinessException("小红书内容请使用服务端下载");
        }
        // 微博：sinaimg CDN 与视频流均校验 Referer，只支持服务端下载
        if (weiboParser.supports(url)) {
            throw new BusinessException("微博内容请使用服务端下载");
        }
        // NetMirror：MP4 直链带 CDN 时效签名，只支持服务端下载
        if (netMirrorParser.supports(url)) {
            throw new BusinessException("NetMirror 视频请使用服务端下载");
        }
        // QQ 音乐：CDN 校验 Referer，只支持服务端下载
        if (qqMusicParser.supports(url)) {
            throw new BusinessException("QQ 音乐请使用服务端下载");
        }
        if (xvideosParser.supports(url)) {
            throw new BusinessException("XVideos 视频为 HLS 流，不支持浏览器直链，请使用服务端下载");
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
        // Amasian TV：HLS 流，不支持浏览器直链，走服务端下载
        if (amasianTvParser.supports(url)) {
            throw new BusinessException("Amasian TV 视频为 HLS 流，不支持浏览器直链，请使用服务端下载");
        }
        // 腾讯视频：官方 CDN 直链带时效 vkey，只支持服务端下载
        if (tencentParser.supports(url)) {
            throw new BusinessException("腾讯视频请使用服务端下载");
        }
        // VIP 解析（优酷/爱奇艺/芒果TV）：HLS 流，只支持服务端下载
        if (vipParser.supports(url)) {
            throw new BusinessException("VIP 解析视频请使用服务端下载");
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
}
