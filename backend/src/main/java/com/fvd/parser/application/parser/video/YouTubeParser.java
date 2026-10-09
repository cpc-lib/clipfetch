package com.fvd.parser.application.parser.video;

import com.fvd.shared.web.BusinessException;
import com.fvd.parser.application.ParseContext;
import com.fvd.parser.application.parser.AbstractVideoParser;
import com.fvd.parser.application.parser.CookiePolicy;
import com.fvd.parser.domain.Platform;
import com.fvd.parser.domain.VideoInfo;
import com.fvd.parser.infrastructure.service.YtDlpService;
import com.fvd.parser.infrastructure.service.YouTubeMirrorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * YouTube 解析：yt-dlp 优先（cookies + deno PO Token 可过反爬）；
 * 被出口 IP 风控时走公共镜像兜底（元数据/字幕不依赖本机出口 IP），镜像不可用才回退原错误。
 */
@Slf4j
@Service
public class YouTubeParser extends AbstractVideoParser {

    private final YtDlpService ytDlp;
    private final YouTubeMirrorService youtubeMirror;

    public YouTubeParser(YtDlpService ytDlp, YouTubeMirrorService youtubeMirror) {
        this.ytDlp = ytDlp;
        this.youtubeMirror = youtubeMirror;
    }

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.OPTIONAL;
    }

    @Override
    public VideoInfo parse(ParseContext ctx) throws BusinessException {
        String url = ctx.url();
        String cookies = ctx.cookies();
        try {
            VideoInfo info = ytDlp.parse(url, cookies);
            if (info.subtitles() == null || info.subtitles().isEmpty()) {
                info = youtubeMirror.enrichSubtitles(info);
            }
            return info;
        } catch (BusinessException e) {
            VideoInfo fallback = youtubeMirror.buildFallbackVideoInfo(url);
            if (fallback != null) {
                log.info("yt-dlp 解析失败（{}），已用镜像兜底: {}", e.getMessage(), fallback.title());
                return fallback;
            }
            throw e;
        }
    }
}
