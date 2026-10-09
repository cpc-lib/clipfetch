package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.infrastructure.service.YtDlpService;

import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * SpankBang 适配层：站点页面和流地址交给 yt-dlp 的专用 extractor 解析。
 */
@Service
public class SpankBangParser extends AbstractVideoParser {

    private static final Pattern VIDEO_PATH = Pattern.compile(
            "^/[0-9a-z]+/(?:video|play|embed)(?:/|$)", Pattern.CASE_INSENSITIVE);

    private final YtDlpService ytDlp;

    public SpankBangParser(YtDlpService ytDlp) {
        this.ytDlp = ytDlp;
    }

    @Override
    public Platform platform() {
        return Platform.SPANKBANG;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    @Override
    public boolean supports(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            host = host.toLowerCase();
            return (host.equals("spankbang.com") || host.endsWith(".spankbang.com"))
                    && VIDEO_PATH.matcher(uri.getPath()).find();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        VideoInfo info = ytDlp.parse(url, null);
        if (info.title() != null && !info.title().isBlank() && !info.title().equals(info.id())) {
            return info;
        }
        String title = titleFromUrl(url);
        if (title == null) {
            return info;
        }
        return new VideoInfo(info.id(), title, info.thumbnail(), info.duration(), info.durationString(),
                info.uploader(), info.platform(), info.viewCount(), info.uploadDate(), info.formats(),
                info.media(), info.subtitles(), info.hasSubtitles());
    }

    private String titleFromUrl(String url) {
        String[] segments = URI.create(url).getRawPath().split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if ("video".equalsIgnoreCase(segments[i]) && !segments[i + 1].isBlank()) {
                String title = URLDecoder.decode(segments[i + 1], StandardCharsets.UTF_8).trim();
                if (!title.isEmpty()) {
                    return Character.toUpperCase(title.charAt(0)) + title.substring(1);
                }
            }
        }
        return null;
    }
}
