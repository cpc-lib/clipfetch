package cc.ivera.parser.application;

import cc.ivera.auth.domain.User;
import cc.ivera.cookie.application.CookieService;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.application.parser.VideoParser;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import cc.ivera.parser.infrastructure.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
     * 解析结果缓存：(userId|url) → VideoInfo，写后 5 分钟过期，容量 1000。
     * 仅缓存成功结果（失败异常不会走到 put）；VideoInfo 为不可变 record，可跨请求共享。
     */
    private final Cache<String, VideoInfo> parseCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1000)
            .build();

    /**
     * 解析视频信息。优先读缓存（同一用户 5 分钟内重复解析同一链接直接返回上次结果），
     * 未命中时遍历已注册 parser 找到第一个 supports(url) 的执行解析；
     * 无匹配时回退到 yt-dlp（OPTIONAL cookie 策略）。
     *
     * <p>带文件库写入副作用的解析（音乐歌单/搜索/歌手/专辑，见
     * {@link VideoParser#hasParseSideEffects}）不进缓存：缓存命中会跳过 temp 表同步，
     * 导致文件库不更新；这类解析本身是 API 调用，无 yt-dlp/浏览器开销。
     */
    public VideoInfo parse(String url, User user) {
        VideoParser matched = null;
        for (VideoParser p : parsers) {
            if (p.supports(url)) {
                matched = p;
                break;
            }
        }
        boolean cacheable = matched == null || !matched.hasParseSideEffects(url);
        String key = (user != null ? user.getId() : 0L) + "|" + url;
        if (cacheable) {
            VideoInfo cached = parseCache.getIfPresent(key);
            if (cached != null) {
                log.info("[ParseCache] 命中缓存: userId={}, url={}", user != null ? user.getId() : null, url);
                return cached;
            }
        }
        VideoInfo info = matched != null ? doParse(matched, url, user) : doFallback(url, user);
        if (cacheable) {
            parseCache.put(key, info);
        }
        return info;
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
