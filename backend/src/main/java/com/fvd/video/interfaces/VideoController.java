package com.fvd.video.interfaces;

import com.fvd.auth.domain.User;
import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import com.fvd.temp.domain.TempLink;
import com.fvd.temp.domain.TempLinkMapper;
import com.fvd.video.application.DownloadService;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import com.fvd.video.infrastructure.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VideoController {

    private final YtDlpService ytDlp;
    private final YouTubeMirrorService youtubeMirror;
    private final DouyinParser douyinParser;
    private final InstagramParser instagramParser;
    private final CctvParser cctvParser;
    private final PornhubParser pornhubParser;
    private final SpankBangParser spankBangParser;
    private final XvideosParser xvideosParser;
    private final MissavParser missavParser;
    private final TubiParser tubiParser;
    private final CgtnParser cgtnParser;
    private final BbcParser bbcParser;
    private final AmasianTvParser amasianTvParser;
    private final VipParser vipParser;
    private final RednoteParser rednoteParser;
    private final WeiboParser weiboParser;
    private final NetMirrorParser netMirrorParser;
    private final QQMusicParser qqMusicParser;
    private final NeteaseMusicParser neteaseMusicParser;
    private final HlsClient hlsClient;
    private final CctvNodeDecryptSidecar cctvNodeDecryptSidecar;
    private final QQMusicBrowserSidecar qqMusicBrowserSidecar;
    private final DownloadService downloadService;
    private final CookieService cookieService;
    private final TempLinkMapper tempLinkMapper;

    private static final HttpClient WALLPAPER_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final String WALLPAPER_FALLBACK =
            "https://www.bing.com/th?id=OHR.ChattoogaRiver_ZH-CN9453791496_1920x1080.jpg";
    private static final com.fasterxml.jackson.databind.ObjectMapper WALLPAPER_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 随机 Bing 每日壁纸：取近 8 天中随机一天的主图并 302 跳转，前端封面兜底用。
     */
    @GetMapping("/wallpaper")
    public void wallpaper(HttpServletResponse response) throws java.io.IOException {
        String location = WALLPAPER_FALLBACK;
        try {
            int idx = ThreadLocalRandom.current().nextInt(8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=" + idx + "&n=1&mkt=zh-CN"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> res = WALLPAPER_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String url = WALLPAPER_MAPPER.readTree(res.body()).path("images").path(0).path("url").asText("");
            if (!url.isBlank()) {
                location = "https://www.bing.com" + url;
            }
        } catch (Exception e) {
            log.debug("随机壁纸获取失败，使用兜底图: {}", e.getMessage());
        }
        response.sendRedirect(location);
    }

    /**
     * 图片代理：sinaimg 等 CDN 校验 Referer，浏览器直链（no-referrer）会 403。
     * 服务端带上平台 Referer 抓取后流式回写，供前端 <img> 安全加载。
     */
    @GetMapping("/image-proxy")
    public void imageProxy(@RequestParam("url") String url, HttpServletResponse response) throws java.io.IOException {
        if (url == null || url.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String referer;
        try {
            String host = URI.create(url).getHost();
            if (host != null && (host.endsWith("sinaimg.cn") || host.endsWith("weibo.com")
                    || host.endsWith("weibo.cn") || host.endsWith("weibocdn.com"))) {
                referer = "https://weibo.com/";
            } else {
                referer = "";
            }
        } catch (Exception e) {
            referer = "";
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                    .header("Accept", "image/*,video/*,*/*;q=0.8")
                    .GET();
            if (!referer.isEmpty()) {
                rb.header("Referer", referer);
            }
            HttpResponse<java.io.InputStream> resp =
                    client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                response.setStatus(resp.statusCode());
                return;
            }
            String ct = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
            response.setContentType(ct);
            response.setHeader("Cache-Control", "public, max-age=3600");
            try (var in = resp.body(); var out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        } catch (Exception e) {
            log.debug("图片代理失败 {}: {}", url, e.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        }
    }

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
     * 解析视频信息。抖音必须登录并配置 cookies；Instagram cookies 可选（匿名走后端代理，被门控时仍需上传）。
     */
    @PostMapping("/parse")
    public ApiResponse<VideoInfo> parse(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user,
            @RequestBody ParseReq req) {
        String url = validateUrl(req.getUrl());
        if (douyinParser.supports(url)) {
            String cookies = cookieService.requireContent(user, Platform.DOUYIN);
            try {
                return ApiResponse.ok(douyinParser.parse(url, cookies));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.DOUYIN, e.getMessage());
                throw e;
            }
        }
        if (instagramParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.INSTAGRAM);
            try {
                return ApiResponse.ok(instagramParser.parse(url, user.getId(), cookies));
            } catch (InstagramParser.LoginRequiredException e) {
                // 门控说明 cookies 无效，标记后直接返回明确提示（回退 yt-dlp 同样拿不到）
                cookieService.markInvalid(user, Platform.INSTAGRAM, e.getMessage());
                throw e;
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.INSTAGRAM, e.getMessage());
                // 图文解析失败时，回退 yt-dlp（普通视频帖）
                return ApiResponse.ok(ytDlp.parse(url, cookies));
            }
        }
        // 小红书：移动端 UA 匿名 SSR 解析图文/视频笔记，不抓评论
        if (rednoteParser.supports(url)) {
            return ApiResponse.ok(rednoteParser.parse(url));
        }
        // 微博：移动端 statuses/show 接口解析图文/视频帖，cookies 可选（限流/私密帖时上传）
        if (weiboParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.WEIBO);
            try {
                return ApiResponse.ok(weiboParser.parse(url, cookies));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.WEIBO, e.getMessage());
                throw e;
            }
        }
        // NetMirror：电视剧多剧集多清晰度，HMAC-SHA256 签名取 watchbox.php 直链
        if (netMirrorParser.supports(url)) {
            return ApiResponse.ok(netMirrorParser.parse(url));
        }
        // QQ 音乐歌单页：公开歌单匿名解析歌曲子链接并保存到 temp 表（登录态走 sidecar，不使用 DB cookies）
        if (qqMusicParser.isPlaylistUrl(url)) {
            QQMusicParser.PlaylistParseResult result = qqMusicParser.parsePlaylist(url);
            int[] counts = saveQQSongsToTemp(result.songs(), url);
            return ApiResponse.ok(new VideoInfo(
                    null, result.playlistName(), null, null, null,
                    null, Platform.QQMUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条"));
        }
        // QQ 音乐：歌曲元信息 + 浏览器 sidecar 取直链（登录态在 sidecar profile，与 DB cookies 无关，
        // 未登录/VIP 无权益等错误直接透传，不标记 cookie 失效）
        if (qqMusicParser.supports(url)) {
            return ApiResponse.ok(qqMusicParser.parse(url));
        }
        // 网易云音乐歌手页：解析热门歌曲子链接并保存到 temp 表
        if (neteaseMusicParser.isArtistUrl(url)) {
            NeteaseMusicParser.ArtistParseResult result = neteaseMusicParser.parseArtist(url);
            int[] counts = saveSongsToTemp(result.songs(), url);
            String title = result.artistName() != null ? result.artistName() : "网易云歌手";
            return ApiResponse.ok(new VideoInfo(
                    null, title, null, null, null,
                    null, Platform.NETEASE_MUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条"));
        }
        // 网易云音乐歌单页：解析歌曲子链接并保存到 temp 表
        if (neteaseMusicParser.isPlaylistUrl(url)) {
            String cookies = cookieService.findContent(user, Platform.NETEASE_MUSIC);
            NeteaseMusicParser.PlaylistParseResult result = neteaseMusicParser.parsePlaylist(url, cookies);
            int[] counts = saveSongsToTemp(result.songs(), url);
            return ApiResponse.ok(new VideoInfo(
                    null, result.playlistName(), null, null, null,
                    null, Platform.NETEASE_MUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条"));
        }
        // 网易云音乐专辑页：解析歌曲子链接并保存到 temp 表
        if (neteaseMusicParser.isAlbumUrl(url)) {
            String cookies = cookieService.findContent(user, Platform.NETEASE_MUSIC);
            NeteaseMusicParser.AlbumParseResult result = neteaseMusicParser.parseAlbum(url, cookies);
            int[] counts = saveSongsToTemp(result.songs(), url);
            return ApiResponse.ok(new VideoInfo(
                    null, result.albumName(), null, null, null,
                    null, Platform.NETEASE_MUSIC.display, null, null,
                    List.of(), null, List.of(), false,
                    "解析到 " + counts[0] + " 首歌曲，保存 " + counts[1] + " 条，跳过重复 " + counts[2] + " 条"));
        }
        // 网易云音乐：歌曲元信息 + 第三方直链（可选 cookies）
        if (neteaseMusicParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.NETEASE_MUSIC);
            try {
                return ApiResponse.ok(neteaseMusicParser.parse(url, cookies));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.NETEASE_MUSIC, e.getMessage());
                throw e;
            }
        }
        if (pornhubParser.supports(url)) {
            return ApiResponse.ok(pornhubParser.parse(url, null));
        }
        if (spankBangParser.supports(url)) {
            return ApiResponse.ok(spankBangParser.parse(url));
        }
        if (xvideosParser.supports(url)) {
            return ApiResponse.ok(xvideosParser.parse(url));
        }
        if (missavParser.supports(url)) {
            return ApiResponse.ok(missavParser.parse(url));
        }
        // CCTV：纯 Java 解析，多清晰度探测（可选 cookies，用于 VIP 内容）
        if (cctvParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.CCTV);
            try {
                return ApiResponse.ok(cctvParser.parse(url, cookies));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.CCTV, e.getMessage());
                throw e;
            }
        }
        // CGTN：纯 Java 解析，从 data-video 属性提取 m3u8
        if (cgtnParser.supports(url)) {
            return ApiResponse.ok(cgtnParser.parse(url, null));
        }
        // BBC：纯 Java 解析（__NEXT_DATA__ → playlist.json → mediaselector API）
        if (bbcParser.supports(url)) {
            return ApiResponse.ok(bbcParser.parse(url, null));
        }
        // Tubi：纯 Java 解析（匿名设备认证链 + CMS v3 API），无需 cookies
        if (tubiParser.supports(url)) {
            return ApiResponse.ok(tubiParser.parse(url));
        }
        // Amasian TV：odkmedia.io API 获取 HLS master m3u8，多清晰度。
        // 服务器在美洲时直连；被地区限制且配置 PROXY_URL 时自动走代理（见 AmasianTvParser）。
        if (amasianTvParser.supports(url)) {
            return ApiResponse.ok(amasianTvParser.parse(url));
        }
        // 腾讯视频/优酷/爱奇艺/芒果TV：通过 vip.61la.com 直取官方 CDN 源（秒级、无水印）
        if (vipParser.supports(url)) {
            return ApiResponse.ok(vipParser.parse(url));
        }
        // YouTube：yt-dlp 优先（cookies + deno PO Token 可过反爬）；被出口 IP 风控时
        // 走公共镜像兜底（元数据/字幕不依赖本机出口 IP），镜像不可用才回退原错误。
        Platform platform = Platform.from(url);
        if (platform == Platform.YOUTUBE) {
            String cookies = cookieService.findContent(user, platform);
            try {
                VideoInfo info = ytDlp.parse(url, cookies);
                if (info.subtitles() == null || info.subtitles().isEmpty()) {
                    info = youtubeMirror.enrichSubtitles(info);
                }
                return ApiResponse.ok(info);
            } catch (BusinessException e) {
                VideoInfo fallback = youtubeMirror.buildFallbackVideoInfo(url);
                if (fallback != null) {
                    log.info("yt-dlp 解析失败（{}），已用镜像兜底: {}", e.getMessage(), fallback.title());
                    return ApiResponse.ok(fallback);
                }
                cookieService.markInvalidIfAuth(user, platform, e.getMessage());
                throw e;
            }
        }
        // Twitter/TikTok/Bilibili 等：登录用户有上传 cookies 就带上，没有则匿名
        String cookies = cookieService.findContent(user, platform);
        try {
            return ApiResponse.ok(ytDlp.parse(url, cookies));
        } catch (BusinessException e) {
            cookieService.markInvalidIfAuth(user, platform, e.getMessage());
            throw e;
        }
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
        // VIP 解析（腾讯/优酷/爱奇艺/芒果TV）：HLS 流，只支持服务端下载
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
                cctvParser.parse(url, cookieContent);
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
            MissavParser.DownloadTarget target = missavParser.resolveDownload(url);
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
                tubiParser.parse(url);
                streamUrl = tubiParser.resolveDownloadUrl(url, req.getFormatId());
            }
            if (streamUrl == null) {
                throw new BusinessException("请先解析视频后再下载");
            }
            downloadService.downloadToResponse(streamUrl, null, title != null ? title : "tubi-video",
                    response, null, req.getTaskId(), List.of("-N", "128", "--socket-timeout", "90"));
            return;
        }
        // SpankBang：目标格式为 m3u8_native（HLS 分片），aria2c 仅加速普通 HTTP 文件，
        // 无法并发 HLS 分片；显式传 -N 让 yt-dlp 并发下载分片（默认 1）
        if (spankBangParser.supports(url)) {
            String cookies = cookieService.findContent(user, Platform.SPANKBANG);
            try {
                downloadService.downloadToResponse(url, req.getFormatId(), title, response, cookies, req.getTaskId(),
                        List.of("-N", "128"));
            } catch (BusinessException e) {
                cookieService.markInvalidIfAuth(user, Platform.SPANKBANG, e.getMessage());
                throw e;
            }
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
        // VIP 解析（腾讯/优酷/爱奇艺/芒果TV）：直接使用解析站下发的源下载，不做回退
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
            downloadService.downloadToResponse(url, req.getFormatId(), title, response, cookies, req.getTaskId());
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

    /** temp 表保存用的最小歌曲结构 */
    private record TempSongInput(String url, String title) {}

    /**
     * 保存网易云歌曲子链接到 temp 表：url 非空且去重。
     * 返回 [解析总数, 保存数, 跳过数]
     */
    private int[] saveSongsToTemp(List<NeteaseMusicParser.ArtistSong> songs, String sourceUrl) {
        return saveTempLinks(songs.stream()
                .map(s -> new TempSongInput(s.url(), s.title())).toList(), sourceUrl);
    }

    /**
     * 保存 QQ 音乐歌曲子链接到 temp 表：url 非空且去重。
     * 返回 [解析总数, 保存数, 跳过数]
     */
    private int[] saveQQSongsToTemp(java.util.List<QQMusicParser.PlaylistSong> songs, String sourceUrl) {
        return saveTempLinks(songs.stream()
                .map(s -> new TempSongInput(s.url(), s.title())).toList(), sourceUrl);
    }

    /**
     * 保存歌曲子链接到 temp 表：url 非空且按 url 去重。
     * 返回 [解析总数, 保存数, 跳过数]
     */
    private int[] saveTempLinks(List<TempSongInput> songs, String sourceUrl) {
        int parsed = songs.size();
        int saved = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (TempSongInput song : songs) {
            if (song.url() == null || song.url().isBlank()) {
                skipped++;
                continue;
            }
            // 按 url 去重
            Long exists = tempLinkMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TempLink>()
                            .eq("url", song.url()));
            if (exists != null && exists > 0) {
                skipped++;
                continue;
            }
            tempLinkMapper.insert(TempLink.builder()
                    .url(song.url())
                    .title(song.title())
                    .sourceUrl(sourceUrl)
                    .downloaded(false)
                    .createdAt(now)
                    .build());
            saved++;
        }
        log.info("temp 保存: 来源={}, 解析={}, 保存={}, 跳过={}", sourceUrl, parsed, saved, skipped);
        return new int[]{parsed, saved, skipped};
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
