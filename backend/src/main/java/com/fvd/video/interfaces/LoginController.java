package com.fvd.video.interfaces;

import com.fvd.auth.domain.User;
import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.shared.web.ApiResponse;
import com.fvd.video.infrastructure.sidecar.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LoginController {

    private final QQMusicBrowserSidecar qqMusicBrowserSidecar;
    private final KugouMusicBrowserSidecar kugouMusicBrowserSidecar;
    private final TencentBrowserSidecar tencentBrowserSidecar;

    /**
     * QQ 音乐扫码登录：在运行本服务的机器上弹出 Chrome 窗口供扫码，
     * 登录态保存在浏览器 sidecar 的持久化用户目录中，供取流接口使用。
     */
    @PostMapping("/qqmusic/login")
    public ApiResponse<Map<String, String>> qqMusicLogin(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        qqMusicBrowserSidecar.login();
        return ApiResponse.ok(Map.of("message", "QQ 音乐扫码登录成功"));
    }

    /**
     * 酷狗扫码登录：在运行本服务的机器上弹出 Chrome 窗口供扫码，
     * 登录态保存在浏览器 sidecar 的持久化用户目录中，供付费/VIP 歌曲取流使用。
     */
    @PostMapping("/kugou/login")
    public ApiResponse<Map<String, String>> kugouLogin(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        kugouMusicBrowserSidecar.login();
        return ApiResponse.ok(Map.of("message", "酷狗扫码登录成功"));
    }

    /**
     * 腾讯视频扫码登录：在运行本服务的机器上弹出 Chrome 窗口供扫码，
     * 登录态保存在浏览器 sidecar 的持久化用户目录中，供 VIP 内容取流使用。
     */
    @PostMapping("/tencent/login")
    public ApiResponse<Map<String, String>> tencentLogin(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        tencentBrowserSidecar.login();
        return ApiResponse.ok(Map.of("message", "腾讯视频扫码登录成功"));
    }
}
