package cc.ivera.parser.application.parser;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;

/**
 * 视频解析策略接口（Strategy 模式）。
 *
 * <p>每个具体 parser 实现此接口，由 {@link cc.ivera.parser.application.ParserRegistry}
 * 按 {@link #supports(String)} 路由分发，替代 VideoController 中的 if-else 链。
 *
 * <p>批量操作（歌单/搜索/歌手/专辑）在各音乐 parser 的 {@link #parse} 内部处理，
 * controller 无需感知子类型。
 */
public interface VideoParser {

    /** 平台标识，用于 cookie 查找和日志。VipParser 等多 host parser 可返回 null。 */
    Platform platform();

    /** URL 是否由本 parser 处理。默认实现见 {@link AbstractVideoParser}。 */
    boolean supports(String url);

    /** Cookie 策略，registry 据此做 cookie 查找和失效标记。 */
    CookiePolicy cookiePolicy();

    /**
     * 解析视频信息。cookie 由 registry 按 {@link #cookiePolicy()} 预先填入 context。
     *
     * @throws BusinessException 解析失败（registry 会按 cookiePolicy 做 markInvalidIfAuth）
     */
    VideoInfo parse(ParseContext ctx) throws BusinessException;
}
