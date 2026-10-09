package com.fvd.parser.application;

import com.fvd.auth.domain.User;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.BusinessException;
import com.fvd.parser.application.parser.CookiePolicy;
import com.fvd.parser.application.parser.VideoParser;
import com.fvd.parser.domain.Platform;
import com.fvd.parser.domain.VideoInfo;
import com.fvd.parser.infrastructure.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Parser 分发注册中心（Strategy + Registry 模式）。
 *
 * <p>Spring 自动收集所有 {@link VideoParser} bean，按 {@link VideoParser#supports(String)}
 * 路由到匹配的 parser，替代 VideoController 中 160 行 if-else 分发链。
 *
 * <p>cookie 查找和失效标记由 registry 统一处理，parser 自身不感知 CookieService：
 * <ul>
 *   <li>NONE — 不查 cookie</li>
 *   <li>OPTIONAL — findContent（可为 null）</li>
 *   <li>REQUIRED — requireContent（无 cookie 抛异常）</li>
 * </ul>
 * parse 失败时，非 NONE 策略自动调 markInvalidIfAuth。
 *
 * <p>无 parser 匹配时，回退到 yt-dlp 处理 Twitter/TikTok/Bilibili 等平台。
 * YouTube 由独立的 YouTubeParser 处理，不进回退分支。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParserRegistry {

    private final List<VideoParser> parsers;
    private final YtDlpService ytDlp;
    private final CookieService cookieService;

    /**
     * 解析视频信息。遍历已注册 parser，找到第一个 supports(url) 的执行解析；
     * 无匹配时回退到 yt-dlp（OPTIONAL cookie 策略）。
     */
    public VideoInfo parse(String url, User user) {
        for (VideoParser p : parsers) {
            if (p.supports(url)) {
                return doParse(p, url, user);
            }
        }
        return doFallback(url, user);
    }

    private VideoInfo doParse(VideoParser p, String url, User user) {
        String cookies = resolveCookies(p.cookiePolicy(), user, p.platform());
        try {
            return p.parse(new ParseContext(url, user, cookies));
        } catch (BusinessException e) {
            if (p.cookiePolicy() != CookiePolicy.NONE) {
                cookieService.markInvalidIfAuth(user, p.platform(), e.getMessage());
            }
            throw e;
        }
    }

    private String resolveCookies(CookiePolicy policy, User user, Platform platform) {
        return switch (policy) {
            case REQUIRED -> cookieService.requireContent(user, platform);
            case OPTIONAL -> cookieService.findContent(user, platform);
            case NONE -> null;
        };
    }

    /**
     * 回退：无特定 parser 匹配的 URL（Twitter/TikTok/Bilibili 等）走 yt-dlp。
     * OPTIONAL cookie 策略，失败时 markInvalidIfAuth。
     */
    private VideoInfo doFallback(String url, User user) {
        Platform platform = Platform.from(url);
        String cookies = cookieService.findContent(user, platform);
        try {
            return ytDlp.parse(url, cookies);
        } catch (BusinessException e) {
            cookieService.markInvalidIfAuth(user, platform, e.getMessage());
            throw e;
        }
    }
}
