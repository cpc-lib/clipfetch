package cc.ivera.parser.application;

import cc.ivera.auth.domain.User;
import cc.ivera.cookie.application.CookieService;
import cc.ivera.parser.application.parser.music.KugouMusicParser;
import cc.ivera.parser.application.parser.music.NeteaseMusicParser;
import cc.ivera.parser.application.parser.music.QQMusicParser;
import cc.ivera.parser.application.parser.video.*;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.infrastructure.service.YtDlpService;
import cc.ivera.parser.infrastructure.service.YouTubeMirrorService;
import cc.ivera.shared.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 按平台解析上游播放地址与请求头，只取地址不下载。
 * 分支与 DownloadController 保持一致，便于排障时对照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackSourceService {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Set<String> AUDIO_EXT = Set.of(
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav");

    private final DirectFileParser directFileParser;
    private final DouyinParser douyinParser;
    private final InstagramParser instagramParser;
    private final QQMusicParser qqMusicParser;
    private final KugouMusicParser kugouMusicParser;
    private final NeteaseMusicParser neteaseMusicParser;
    private final NetMirrorParser netMirrorParser;
    private final TubiParser tubiParser;
    private final BbcParser bbcParser;
    private final CgtnParser cgtnParser;
    private final AmasianTvParser amasianTvParser;
    private final TencentParser tencentParser;
    private final CctvParser cctvParser;
    private final VipParser vipParser;
    private final HuaVodNetParser huaVodNetParser;
    private final IfyTvParser ifyTvParser;
    private final RednoteParser rednoteParser;
    private final WeiboParser weiboParser;
    private final YouTubeMirrorService youtubeMirror;
    private final YtDlpService ytDlp;
    private final CookieService cookieService;

    public Resolved resolve(String url, String formatId, String title, User user) {
        boolean mergeRequest = formatId != null && formatId.contains("+");
        if (mergeRequest && Platform.from(url) != Platform.YOUTUBE) {
            throw new BusinessException("该清晰度需要音视频合并，暂不支持在线播放，请下载后观看");
        }
        String pageUrl = url;

        if (directFileParser.supports(url)) {
            String name = title != null && !title.isBlank() ? title : directFileParser.filenameFromUrl(url);
            return build(url, url, null, null, false, name);
        }
        if (douyinParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.DOUYIN);
            String direct = douyinParser.tryDirectUrl(url, cookies);
            if (direct != null) {
                return build(url, direct, "https://www.douyin.com/", cookies, false, title);
            }
            return build(url, ytDlp.directUrl(url, formatId, cookies),
                    "https://www.douyin.com/", cookies, false, title);
        }
        if (instagramParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.INSTAGRAM);
            return build(url, ytDlp.directUrl(url, formatId, cookies), null, cookies, false, title);
        }
        if (qqMusicParser.supports(url)) {
            String audioUrl = qqMusicParser.resolveAudioUrl(url);
            if (audioUrl == null) {
                throw new BusinessException("请先解析歌曲后再播放");
            }
            return build(url, audioUrl, "https://y.qq.com/", null, false, title);
        }
        if (kugouMusicParser.supports(url)) {
            return build(url, kugouMusicParser.resolveAudioUrl(url),
                    "https://www.kugou.com/", null, false, title);
        }
        if (neteaseMusicParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.NETEASE_MUSIC);
            String audioUrl = neteaseMusicParser.resolveAudioUrl(url, cookies, formatId);
            if (audioUrl == null) {
                throw new BusinessException("请先解析歌曲后再播放");
            }
            return build(url, audioUrl, "https://music.163.com/", cookies, false, title);
        }
        if (netMirrorParser.supports(url)) {
            String direct = netMirrorParser.resolveDownloadUrl(url, formatId);
            if (direct == null) {
                netMirrorParser.parse(new ParseContext(url, user, null));
                direct = netMirrorParser.resolveDownloadUrl(url, formatId);
            }
            if (direct == null) {
                throw new BusinessException("请先解析视频后再播放");
            }
            if (direct.contains(".mpd") || direct.contains("type=dash") || direct.contains("/dash/")) {
                return new Resolved(URI.create(direct), "dash", "application/dash+xml", false, false,
                        null, null, UA, title);
            }
            // NetMirror 直链 CDN（hakunaymatata 或 tmdb 线路）不接受第三方失效 Referer
            return build(url, direct, null, null, false, title);
        }
        if (tubiParser.supports(url)) {
            String stream = tubiParser.resolveStreamUrl(url, formatId);
            if (stream == null) {
                throw new BusinessException("请先解析视频后再播放");
            }
            return build(url, stream, null, null, false, title);
        }
        if (bbcParser.supports(url)) {
            String stream = bbcParser.resolveStreamUrl(url, formatId);
            if (stream == null) {
                throw new BusinessException("请先解析视频后再播放");
            }
            return build(url, stream, null, null, false, title);
        }
        if (cgtnParser.supports(url)) {
            String stream = cgtnParser.resolveStreamUrl(url, formatId);
            if (stream == null) {
                throw new BusinessException("请先解析视频后再播放");
            }
            return build(url, stream, null, null, false, title);
        }
        if (amasianTvParser.supports(url)) {
            String stream = amasianTvParser.resolveStreamUrl(url, formatId);
            if (stream == null) {
                throw new BusinessException("请先解析视频后再播放");
            }
            return build(url, stream, null, null, false, title);
        }
        if (tencentParser.supports(url)) {
            String direct = tencentParser.directUrl(url, formatId);
            if (direct == null) {
                throw new BusinessException("请先解析视频后再播放，或检查腾讯视频登录态");
            }
            return build(url, direct, "https://v.qq.com/", null, false, title);
        }
        if (cctvParser.supports(url)) {
            String stream = cctvParser.resolvePlainStreamUrl(url, formatId);
            if (stream != null) {
                log.info("CCTV 在线播放走明文 HLS: formatId={}, url={}", formatId, stream);
                return build(url, stream, "https://tv.cctv.com/", null, false, title);
            }
            // h5e 加密流由前端 Web Worker 逐分片解密，首段完成即可交给 hls.js 播放
            String encrypted = cctvParser.resolveEncryptedStreamUrl(url, formatId);
            if (encrypted == null) {
                throw new BusinessException("无法获取 CCTV 视频流地址");
            }
            log.info("CCTV 在线播放走 h5e 浏览器逐分片解密: formatId={}, url={}", formatId, encrypted);
            return new Resolved(URI.create(encrypted), "hls-decrypt", "application/vnd.apple.mpegurl", false, false,
                    "https://tv.cctv.com/", null, UA, title);
        }
        if (vipParser.supports(url)) {
            VipParser.VipStream stream = vipParser.resolveStreamUrl(url, formatId);
            return build(url, stream.url(), stream.referer(), null, false, title,
                    stream.userAgent());
        }
        if (ifyTvParser.supports(url)) {
            String stream = ifyTvParser.resolveStreamUrl(url, formatId);
            return build(url, stream, "https://www.kubb.tv/", null, false, title);
        }
        if (rednoteParser.supports(url)) {
            String stream = rednoteParser.resolveVideoUrl(url);
            if (stream == null) {
                throw new BusinessException("该内容不是单个视频，暂不支持在线播放");
            }
            return build(url, stream, "https://www.xiaohongshu.com/", null, false, title);
        }
        if (weiboParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.WEIBO);
            String stream = weiboParser.resolveVideoUrl(url, cookies);
            if (stream == null) {
                throw new BusinessException("该内容不是单个视频，暂不支持在线播放");
            }
            return build(url, stream, "https://weibo.com/", cookies, false, title);
        }
        if (huaVodNetParser.supports(url)) {
            // 华视取流需等待 15~25 秒广告，票据创建为同步阻塞
            String stream = huaVodNetParser.resolveStreamUrl(url, formatId);
            return build(url, stream, "https://huavod.net/", null, false, title);
        }
        Platform platform = Platform.from(pageUrl);
        if (platform == Platform.YOUTUBE && mergeRequest) {
            // 高清音视频分离格式：后台 yt-dlp 合并落盘，票据指向本地文件
            String cookies = cookieService.findContent(user, Platform.YOUTUBE);
            return new Resolved(URI.create(url), "merge", "video/mp4", false, false,
                    "https://www.youtube.com/", cookies, UA, title);
        }
        if (platform == Platform.YOUTUBE && formatId != null && formatId.startsWith("IV")) {
            String direct = youtubeMirror.resolveStreamUrl(url, formatId);
            if (direct == null) {
                throw new BusinessException("镜像视频流不可用，请重新解析后再试");
            }
            return build(url, direct, "https://www.youtube.com/", null, true, title);
        }
        String cookies = cookieService.findContent(user, platform);
        return build(url, ytDlp.directUrl(url, formatId, cookies), null, cookies, false, title);
    }

    private Resolved build(String pageUrl, String upstream, String referer, String cookie,
                           boolean forceProxy, String title) {
        return build(pageUrl, upstream, referer, cookie, forceProxy, title, UA);
    }

    private Resolved build(String pageUrl, String upstream, String referer, String cookie,
                           boolean forceProxy, String title, String userAgent) {
        URI uri;
        try {
            uri = URI.create(upstream);
        } catch (Exception e) {
            throw new BusinessException("获取播放地址失败：地址格式不正确");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean useProxy = forceProxy || host.contains("googlevideo") || host.contains("youtube.com");
        boolean hls = uri.toString().toLowerCase(Locale.ROOT).contains(".m3u8");
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String ext = path.lastIndexOf('.') > 0 ? path.substring(path.lastIndexOf('.') + 1) : "";
        boolean audioOnly = AUDIO_EXT.contains(ext)
                || (referer != null && (referer.contains("y.qq.com")
                || referer.contains("kugou.com") || referer.contains("music.163.com")));
        String mime = hls ? "application/vnd.apple.mpegurl" : mimeOf(ext, audioOnly);
        String finalTitle = title != null && !title.isBlank() ? title
                : (ext.isEmpty() ? null : directFileTitle(uri, ext));
        log.info("播放源解析: host={}, mode={}", host, hls ? "hls" : "progressive");
        return new Resolved(uri, hls ? "hls" : "progressive", mime, audioOnly, useProxy,
                referer, cookie, userAgent, finalTitle);
    }

    private String directFileTitle(URI uri, String ext) {
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        int i = path.lastIndexOf('/');
        String name = i >= 0 ? path.substring(i + 1) : path;
        return name.isBlank() ? null : name;
    }

    private String mimeOf(String ext, boolean audioOnly) {
        return switch (ext) {
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "wav" -> "audio/wav";
            default -> audioOnly ? "audio/mpeg" : "video/mp4";
        };
    }

    public record Resolved(URI upstreamUri, String mode, String mime, boolean audioOnly,
                           boolean useProxy, String referer, String cookie, String userAgent,
                           String title) {
    }
}
