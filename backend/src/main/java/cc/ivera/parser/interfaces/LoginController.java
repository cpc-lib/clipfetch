package cc.ivera.parser.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.parser.infrastructure.sidecar.*;
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

    /**
     * QQ 音乐清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie，
     * 便于更换账号后重新扫码登录。
     */
    @PostMapping("/qqmusic/logout")
    public ApiResponse<Map<String, String>> qqMusicLogout(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        qqMusicBrowserSidecar.logout();
        return ApiResponse.ok(Map.of("message", "QQ 音乐登录态已清除"));
    }

    /**
     * 酷狗清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie，
     * 便于更换账号后重新扫码登录。
     */
    @PostMapping("/kugou/logout")
    public ApiResponse<Map<String, String>> kugouLogout(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        kugouMusicBrowserSidecar.logout();
        return ApiResponse.ok(Map.of("message", "酷狗登录态已清除"));
    }

    /**
     * 腾讯视频清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie，
     * 便于更换账号后重新扫码登录。
     */
    @PostMapping("/tencent/logout")
    public ApiResponse<Map<String, String>> tencentLogout(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        tencentBrowserSidecar.logout();
        return ApiResponse.ok(Map.of("message", "腾讯视频登录态已清除"));
    }
}
