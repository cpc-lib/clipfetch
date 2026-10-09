package cc.ivera.parser.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.cookie.application.CookieService;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.temp.domain.TempLink;
import cc.ivera.temp.domain.TempLinkMapper;
import cc.ivera.parser.application.DownloadService;
import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.application.parser.video.*;
import cc.ivera.parser.application.parser.music.*;
import cc.ivera.parser.infrastructure.sidecar.*;
import cc.ivera.parser.infrastructure.service.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadController {

    private final YtDlpService ytDlp;
    private final YouTubeMirrorService youtubeMirror;
    private final DouyinParser douyinParser;
    private final InstagramParser instagramParser;
    private final CctvParser cctvParser;
    private final SpankBangParser spankBangParser;
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
    private final NeteaseMusicParser neteaseMusicParser;
    private final KugouMusicParser kugouMusicParser;
    private final HlsClient hlsClient;
    private final CctvNodeDecryptSidecar cctvNodeDecryptSidecar;
    private final DownloadService downloadService;
    private final CookieService cookieService;
    private final TempLinkMapper tempLinkMapper;

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
            String cookies = cookieService.findContent(user, Platform.INSTAGRAM);
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
        // 小红书图文/视频：服务端代理下载/打包（formatId 为 images 或空都走该通道）
        if (rednoteParser.supports(url)
                && (req.getFormatId() == null || RednoteParser.FORMAT_ID.equals(req.getFormatId()))) {
            rednoteParser.download(url, title, response);
            return;
        }
        // 微博图文/视频：服务端代理下载/打包（formatId 为 images 或空都走该通道）
        if (weiboParser.supports(url)
                && (req.getFormatId() == null || WeiboParser.FORMAT_ID.equals(req.getFormatId()))) {
            String cookies = cookieService.findContent(user, Platform.WEIBO);
            weiboParser.download(url, title, response, cookies);
            return;
        }
        // NetMirror：按 formatId（seXepY-height）重新解析该集取 MP4 直链，
        // 走 yt-dlp + aria2c 多连接分块下载提速（CDN 支持 Range）。
        // CDN（hakunaymatata）校验 Referer，必须是 movieboxonline.net，否则 429
        if (netMirrorParser.supports(url)) {
            String direct = netMirrorParser.resolveDownloadUrl(url, req.getFormatId());
            downloadService.downloadToResponse(direct, null,
                    title != null ? title : "netmirror-video", response, null, req.getTaskId(),
                    List.of("-N", "16", "--add-headers", "Referer:https://movieboxonline.net/"));
            return;
        }
        // QQ 音乐：取 sidecar 解析缓存的直链，服务端流式转发（CDN 校验 Referer）。
        // 登录态在 sidecar profile，错误直接透传，不标记 DB cookie
        if (qqMusicParser.supports(url)) {
            String audioUrl = qqMusicParser.resolveAudioUrl(url);
            if (audioUrl == null) {
                throw new BusinessException("请先解析歌曲后再下载");
            }
            downloadService.downloadDirectToResponse(audioUrl, "https://y.qq.com/",
                    title != null ? title : "qqmusic", response, req.getTaskId());
            // 流式转发正常结束即下载完成：标记 temp 表对应链接为已下载（仅便于查阅，不限制重复下载）
            TempLink upd = new TempLink();
            upd.setDownloaded(true);
            int rows = tempLinkMapper.update(upd,
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TempLink>().eq("url", url));
            if (rows > 0) {
                log.info("temp 标记已下载: url={}", url);
            }
            return;
        }
        // 酷狗音乐：重新取新鲜直链，服务端流式转发（CDN 不校验 Referer）
        if (kugouMusicParser.supports(url)) {
            String audioUrl = kugouMusicParser.resolveAudioUrl(url);
            String filename = kugouMusicParser.resolveFilename(url);
            downloadService.downloadDirectToResponse(audioUrl, "https://www.kugou.com/",
                    filename, response, req.getTaskId());
            // 流式转发正常结束即下载完成：标记 temp 表对应链接为已下载（仅便于查阅，不限制重复下载）
            TempLink upd = new TempLink();
            upd.setDownloaded(true);
            int rows = tempLinkMapper.update(upd,
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TempLink>().eq("url", url));
            if (rows > 0) {
                log.info("temp 标记已下载: url={}", url);
            }
            return;
        }
        // 网易云音乐：按所选品质取直链，服务端流式转发
        if (neteaseMusicParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.NETEASE_MUSIC);
            try {
                String audioUrl = neteaseMusicParser.resolveAudioUrl(url, cookies, req.getFormatId());
                if (audioUrl == null) {
                    throw new BusinessException("请先解析歌曲后再下载");
                }
                // 文件名由后端生成：歌手 - 标题.扩展名（从解析结果中取）
                String filename = neteaseMusicParser.resolveFilename(url, req.getFormatId());
                downloadService.downloadDirectToResponse(audioUrl, "https://music.163.com/",
                        filename, response, req.getTaskId());
                // 流式转发正常结束即下载完成：标记 temp 表对应链接为已下载（仅便于查阅，不限制重复下载）
                TempLink upd = new TempLink();
                upd.setDownloaded(true);
                int rows = tempLinkMapper.update(upd,
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TempLink>().eq("url", url));
                if (rows > 0) {
                    log.info("temp 标记已下载: url={}", url);
                }
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.NETEASE_MUSIC, e.getMessage());
                throw e;
            }
            return;
        }
        // CCTV：用 HlsClient 原生下载 m3u8 分片 → 合并 MP4（不经过 yt-dlp）
        if (cctvParser.supports(url)) {
            String cookieContent = cookieService.findContent(user, Platform.CCTV);
            String streamUrl = cctvParser.resolveDownloadUrl(url, req.getFormatId());
            CctvParser.CctvDownloadParams params = cctvParser.resolveDownloadParams(url, req.getFormatId());
            if (streamUrl == null || params.masterUrl() == null) {
                // 缓存丢失（服务器重启后），重新解析填充缓存
                cctvParser.parse(new ParseContext(url, user, cookieContent));
                streamUrl = cctvParser.resolveDownloadUrl(url, req.getFormatId());
                params = cctvParser.resolveDownloadParams(url, req.getFormatId());
            }
            String cookieHeader = cctvParser.getCookieHeader(cookieContent);
            if (streamUrl != null && params.masterUrl() != null) {
                // h5e 端点被 WASM 加密，走 Node.js 批量解密 sidecar（Playwright 页面 WASM，约 20x 速度）
                try {
                    cctvNodeDecryptSidecar.decryptAndDownload(streamUrl,
                            title != null ? title : "cctv-video", response, req.getTaskId());
                } catch (BusinessException e) {
                    cookieService.markInvalidIfAuth(user, Platform.CCTV, e.getMessage());
                    throw e;
                }
                return;
            }
            if (streamUrl != null) {
                // 未加密端点回退：HlsClient + ffmpeg 直接合并
                try {
                    hlsClient.downloadToResponse(streamUrl, title, cookieHeader, response, req.getTaskId());
                } catch (BusinessException e) {
                    cookieService.markInvalidIfAuth(user, Platform.CCTV, e.getMessage());
                    throw e;
                }
                return;
            }
        }
        // MissAV：从页面还原 surrit HLS 主清单，并用 yt-dlp 浏览器模拟请求清单和分片。
        if (missavParser.supports(url)) {
            MissavParser.DownloadTarget target = missavParser.resolveDownload(url,
                    cookieService.findContent(user, Platform.MISSAV));
            downloadService.downloadToResponse(target.masterUrl(), req.getFormatId(),
                    title != null ? title : "missav-video", response, null, req.getTaskId(),
                    target.ytDlpArgs());
            return;
        }
        // CGTN：HLS m3u8 被墙，走代理下载
        if (cgtnParser.supports(url)) {
            cgtnParser.download(url, req.getFormatId(),
                    title != null ? title : "cgtn-video", response, req.getTaskId());
            return;
        }
        // BBC：HLS 带签名 token，走 HlsClient 服务端下载
        if (bbcParser.supports(url)) {
            bbcParser.download(url, req.getFormatId(),
                    title != null ? title : "bbc-video", response, req.getTaskId());
            return;
        }
        // Tubi：ffmpeg 直连 m3u8 经代理拉分片会出现 byte-range 数据错位，
        // 改走 yt-dlp（原生 HLS 下载器正确处理 EXT-X-BYTERANGE + 音频分离组），-N 128 并发加速
        if (tubiParser.supports(url)) {
            String streamUrl = tubiParser.resolveDownloadUrl(url, req.getFormatId());
            if (streamUrl == null) {
                // 缓存丢失（服务器重启后），重新解析填充缓存
                tubiParser.parse(new ParseContext(url, user, null));
                streamUrl = tubiParser.resolveDownloadUrl(url, req.getFormatId());
            }
            if (streamUrl == null) {
                throw new BusinessException("请先解析视频后再下载");
            }
            downloadService.downloadToResponse(streamUrl, null, title != null ? title : "tubi-video",
                    response, null, req.getTaskId(), List.of("-N", "128", "--socket-timeout", "90"));
            return;
        }
        // SpankBang：页面受 CF Bot Management 保护（yt-dlp 无法抓页面），下载复用
        // sidecar 解析的 CDN 直链（无 CF 挑战）；直链 mp4 为 generic 单格式，格式已在
        // resolveDownload 中按请求档位选好，这里传 null 避免 -f 选择器不匹配
        if (spankBangParser.supports(url)) {
            SpankBangParser.DownloadTarget target = spankBangParser.resolveDownload(url, req.getFormatId());
            downloadService.downloadToResponse(target.url(), null,
                    title != null ? title : "spankbang-video", response, null, req.getTaskId(),
                    target.ytDlpArgs());
            return;
        }
        // XVideos：页面内嵌 HLS 主清单，具体清晰度 playlist 交由 yt-dlp 原生 HLS 下载器
        // 并发分片下载（-N 16，ffmpeg 顺序下载单连接过慢）；
        // 大陆不可达时显式传 --proxy（流地址在 CDN 域名上，平台探测识别不到），美国直连。
        if (xvideosParser.supports(url)) {
            String streamUrl = xvideosParser.resolveStreamUrl(url, req.getFormatId());
            if (streamUrl == null) {
                throw new BusinessException("请先解析视频后再下载");
            }
            List<String> args = new java.util.ArrayList<>(List.of("-N", "16"));
            // yt-dlp generic 提取器默认发 HEAD 探测，G-Core CDN 对 HEAD 返回 400；
            // --no-check-formats 跳过探测直接下载
            args.add("--no-check-formats");
            args.add("--referer");
            args.add(url);
            args.add("--user-agent");
            args.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");
            if (ytDlp.needsProxyFor(Platform.XVIDEOS)) {
                args.add("--proxy");
                args.add(ytDlp.proxy());
            }
            downloadService.downloadToResponse(streamUrl, null,
                    title != null ? title : "xvideos-video", response, null, req.getTaskId(), args);
            return;
        }
        // Amasian TV：HLS 流，用 yt-dlp 并发分片下载（-N 128），CloudFront CDN 国内直连快速。
        if (amasianTvParser.supports(url)) {
            String streamUrl = amasianTvParser.resolveStreamUrl(url, req.getFormatId());
            downloadService.downloadToResponse(streamUrl, null,
                    title != null ? title : "amasian-video", response, null, req.getTaskId(),
                    List.of("-N", "128", "--socket-timeout", "90"));
            return;
        }
        // 腾讯视频：浏览器 sidecar 取官方 CDN 直链，yt-dlp 多连接下载
        if (tencentParser.supports(url)) {
            tencentParser.download(url, req.getFormatId(),
                    title != null ? title : "tencent-video", response, req.getTaskId());
            return;
        }
        // VIP 解析（优酷/爱奇艺/芒果TV）：直接使用解析站下发的源下载，不做回退
        if (vipParser.supports(url)
                && (req.getFormatId() == null || VipParser.FORMAT_ID.equals(req.getFormatId()))) {
            vipParser.download(url, req.getFormatId(),
                    title != null ? title : "vip-video", response, req.getTaskId());
            return;
        }
        // YouTube：镜像解析结果携带的渐进式流直链（formatId 形如 IV18）直接下载；
        // yt-dlp 可用时 formatId 为其原生 ID，继续走通用 yt-dlp 路径
        Platform platform = Platform.from(url);
        if (platform == Platform.YOUTUBE && req.getFormatId() != null && req.getFormatId().startsWith("IV")) {
            String direct = youtubeMirror.resolveStreamUrl(url, req.getFormatId());
            if (direct != null) {
                downloadService.downloadDirectToResponse(direct, "https://www.youtube.com/",
                        title != null ? title : "youtube-video", response, req.getTaskId());
                return;
            }
            throw new BusinessException("镜像视频流不可用，请重新解析后再试");
        }
        String cookies = cookieService.findContent(user, platform);
        try {
            // Bilibili CDN 与 Pornhub 临时签名 HLS 均不适合 aria2c，改由 yt-dlp 原生下载器处理。
            List<String> extraArgs;
            switch (platform) {
                case BILIBILI -> extraArgs = List.of("--downloader", "native");  // B站 CDN 对 aria2c 多连接不稳定
                case PORNHUB -> extraArgs = List.of("-N", "128", "--downloader", "native");  // 直链 MP4 限速 500KB/s，HLS 分片不限速
                default -> extraArgs = List.of();
            }
            downloadService.downloadToResponse(url, req.getFormatId(), title, response, cookies, req.getTaskId(), extraArgs);
        } catch (BusinessException e) {
            cookieService.markInvalidIfAuth(user, platform, e.getMessage());
            throw e;
        }
    }

    /**
     * 单独下载字幕文件（支持 Amasian TV 的 HLS WebVTT 轨道、YouTube 的人工/自动字幕）。
     * 前端在解析结果中发现 hasSubtitles=true 时显示"下载字幕"按钮，
     * 点击后调用此接口，返回字幕文件。
     */
    @PostMapping("/download-subtitle")
    public void downloadSubtitle(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody DownloadReq req, HttpServletResponse response) {
        String url = validateUrl(req.getUrl());
        String title = req.getTitle();

        // Amasian TV：从缓存获取 master playlist URL，yt-dlp 从中发现并下载字幕轨道
        if (amasianTvParser.supports(url)) {
            List<HlsClient.SubtitleTrack> subs = amasianTvParser.getSubtitleTracks(url);
            if (subs.isEmpty()) {
                throw new BusinessException("该视频没有可用字幕");
            }
            String masterUrl = amasianTvParser.getMasterUrl(url);
            downloadService.downloadSubtitleToResponse(masterUrl,
                    title != null ? title : "subtitle", response);
            return;
        }

        // YouTube：镜像字幕内容优先（不依赖本机出口 IP 风控状态，实例恢复后立即可用）；
        // 镜像拿不到再走 yt-dlp 轨道直链（需 cookies/PO Token 通过反爬）
        if (Platform.from(url) == Platform.YOUTUBE) {
            String subLang = req.getSubtitleLang();
            YouTubeMirrorService.CaptionContent cc = youtubeMirror.fetchCaptionContent(url, subLang);
            if (cc != null) {
                try {
                    downloadService.writeTextAttachment(cc.content(),
                            (title != null ? title : "subtitle") + "." + cc.lang() + ".vtt",
                            "text/vtt; charset=utf-8", response);
                } catch (java.io.IOException e) {
                    log.warn("字幕响应写出失败: {}", e.getMessage());
                }
                return;
            }
            try {
                String cookies = cookieService.findContent(user, Platform.YOUTUBE);
                downloadService.downloadYoutubeSubtitleToResponse(url,
                        title != null ? title : "subtitle", subLang, cookies, response);
            } catch (BusinessException e) {
                throw new BusinessException(
                        "字幕下载失败：镜像字幕源暂不可用，且本机访问 YouTube 受限。请上传 YouTube cookies 或稍后重试");
            }
            return;
        }

        throw new BusinessException("当前平台暂不支持独立字幕下载");
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
    public static class DownloadReq {
        private String url;
        private String formatId;
        private String title;
        /**
         * 字幕语言代码（如 zh-Hans/en）：YouTube 独立字幕下载时由前端选择，为空按中文优先自动选轨
         */
        private String subtitleLang;
        /**
         * 下载进度推送标识：前端建立 ws 连接后传入，后端据此推送进度
         */
        private String taskId;
    }
}
