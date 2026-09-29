package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.Platform;
import com.fvd.video.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube 镜像兜底：出口 IP 被 YouTube 风控（"Sign in to confirm you're not a bot"）时，
 * 通过公共 Invidious 实例获取视频元数据、字幕轨道，oEmbed 作为最后兜底；
 * 字幕内容走"Invidious → 云端转录服务（第三方服务器代理访问 YouTube）→ yt-dlp"三级兜底。
 * 镜像/云端服务的服务器代替本机与 YouTube 通信，不依赖本机出口 IP 的风控状态。
 */
@Slf4j
@Service
public class YouTubeMirrorService {

    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
    /** 字幕语言优先级：简体 → 繁体 → 英文 → 日韩 */
    private static final List<String> LANG_PRIORITY = List.of(
            "zh-Hans", "zh-CN", "zh-SG", "zh", "zh-Hant", "zh-TW", "zh-HK", "en-US", "en-GB", "en", "ja", "ko");
    private static final DateTimeFormatter UPLOAD_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final List<String> instances;
    private final String proxy;
    private final ObjectMapper mapper = new ObjectMapper();

    public YouTubeMirrorService(
            @Value("${app.youtube-mirror-apis:https://invidious.f5.si,https://inv.nadeko.net}") String instanceConfig,
            @Value("${app.proxy:}") String proxy) {
        this.instances = instanceConfig == null || instanceConfig.isBlank()
                ? List.of("https://invidious.f5.si", "https://inv.nadeko.net")
                : Arrays.stream(instanceConfig.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.proxy = proxy;
    }

    // ===== 对外能力 =====

    /** 从各类 YouTube 链接（watch/youtu.be/shorts/live/embed）提取 11 位视频 ID */
    public String extractVideoId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** 字幕轨道：显示名、语言代码、实例内相对路径（/api/v1/captions/{id}?label=...） */
    public record CaptionEntry(String label, String lang, String path) {
    }

    /** 镜像视频元数据：formats 仅含实例提供的渐进式流（可能为空），captions 为字幕轨道 */
    public record MirrorMeta(String videoId, String title, String author, String thumbnail,
                             Long durationSeconds, Long viewCount, String uploadDate,
                             List<FormatInfo> formats, List<CaptionEntry> captions) {
    }

    /** 字幕内容：语言代码 + WebVTT 文本 */
    public record CaptionContent(String lang, String content) {
    }

    /**
     * yt-dlp 解析成功但未见字幕轨道时，用镜像字幕列表补齐；镜像不可用或无字幕时原样返回。
     */
    public VideoInfo enrichSubtitles(VideoInfo info) {
        if (info == null || info.id() == null) {
            return info;
        }
        try {
            List<String> langs = captionLangs(listCaptions(info.id()));
            if (langs.isEmpty()) {
                return info;
            }
            log.info("镜像补齐字幕轨道: {} -> {}", info.id(), langs);
            return withSubtitles(info, langs);
        } catch (Exception e) {
            log.warn("镜像字幕补齐失败: {}", e.getMessage());
            return info;
        }
    }

    /** 把字幕轨道列表合并进已解析的 VideoInfo */
    public VideoInfo withSubtitles(VideoInfo info, List<String> langs) {
        return new VideoInfo(info.id(), info.title(), info.thumbnail(), info.duration(), info.durationString(),
                info.uploader(), info.platform(), info.viewCount(), info.uploadDate(),
                info.formats(), info.media(), langs, true);
    }

    /**
     * yt-dlp 被风控时的兜底解析：Invidious 元数据（含字幕轨道）→ oEmbed 兜底。
     * 全部不可用时返回 null（调用方回退原错误）。
     */
    public VideoInfo buildFallbackVideoInfo(String url) {
        String videoId = extractVideoId(url);
        if (videoId == null) {
            return null;
        }
        MirrorMeta meta = fetchMeta(videoId);
        List<CaptionEntry> captions = meta != null && !meta.captions().isEmpty()
                ? meta.captions() : listCaptions(videoId);
        List<String> langs = captionLangs(captions);
        if (meta != null) {
            Long dur = meta.durationSeconds();
            return new VideoInfo(videoId, meta.title(), meta.thumbnail(),
                    dur, dur != null && dur > 0 ? YtDlpService.formatDuration(dur) : null,
                    meta.author(), Platform.YOUTUBE.display, meta.viewCount(), meta.uploadDate(),
                    meta.formats(), null, langs, !langs.isEmpty());
        }
        JsonNode oe = fetchOEmbed(videoId);
        if (oe == null) {
            return null;
        }
        log.info("镜像元数据不可用，oEmbed 兜底: {}", videoId);
        return new VideoInfo(videoId, oe.path("title").asText("未知标题"),
                oe.path("thumbnail_url").asText(null), null, null,
                oe.path("author_name").asText(null), Platform.YOUTUBE.display, null, null,
                List.of(), null, langs, !langs.isEmpty());
    }

    /**
     * 字幕内容下载：按语言优先级选轨，逐实例拉取 WebVTT 文本；全部失败返回 null。
     */
    public CaptionContent fetchCaptionContent(String url) {
        return fetchCaptionContent(url, null);
    }

    /**
     * 字幕内容下载。preferredLang 非空时精确选择该语言（再退基础语言，如 zh-Hans→zh），
     * 该语言无轨道时返回 null；为空时按语言优先级自动选轨。
     */
    public CaptionContent fetchCaptionContent(String url, String preferredLang) {
        String videoId = extractVideoId(url);
        List<CaptionEntry> captions = listCaptions(videoId);
        if (captions.isEmpty()) {
            return null;
        }
        CaptionEntry best;
        if (preferredLang != null && !preferredLang.isBlank()) {
            best = captions.stream().filter(c -> preferredLang.equals(c.lang())).findFirst().orElse(null);
            if (best == null) {
                int dash = preferredLang.indexOf('-');
                String base = dash > 0 ? preferredLang.substring(0, dash) : null;
                if (base != null) {
                    best = captions.stream().filter(c -> base.equals(c.lang())).findFirst().orElse(null);
                }
            }
            if (best == null) {
                log.warn("镜像无该语言字幕轨道: {} {}", videoId, preferredLang);
                return null;
            }
        } else {
            best = captions.stream()
                    .min(Comparator.comparingInt(c -> {
                        int i = LANG_PRIORITY.indexOf(c.lang());
                        return i >= 0 ? i : LANG_PRIORITY.size();
                    }))
                    .orElse(null);
        }
        if (best == null) {
            return null;
        }
        for (String base : instances) {
            String body = get(base + best.path());
            if (body != null && !body.isBlank()) {
                log.info("镜像字幕内容获取成功: {} {} ({} chars)", base, best.lang(), body.length());
                return new CaptionContent(best.lang(), body);
            }
        }
        log.warn("镜像字幕内容全部不可用: {} {}，转云端转录源", videoId, best.lang());
        CaptionContent cloud = fetchCloudTranscript(videoId, best.lang());
        if (cloud != null) {
            return cloud;
        }
        log.warn("云端转录源也不可用: {} {}", videoId, best.lang());
        return null;
    }

    /**
     * 云端转录源（youtube-transcript.ai）：由第三方服务器代理访问 YouTube 并返回带时间戳的
     * markdown 转录，不受本机出口 IP 风控影响。服务对不支持的语言会静默回退英语，
     * 因此必须校验响应头 "Language: xx" 与请求语言一致；正文段落 [m:ss] 文本 转为 WebVTT。
     */
    private CaptionContent fetchCloudTranscript(String videoId, String lang) {
        String url = "https://youtube-transcript.ai/transcript/" + videoId + ".txt?lang=" + lang;
        String md = get(url, Duration.ofSeconds(25));
        if (md == null || md.isBlank() || !md.contains("## Transcript")) {
            return null;
        }
        Matcher head = Pattern.compile("(?m)^Language:\\s*([A-Za-z0-9-]+)").matcher(md);
        String actualLang = head.find() ? head.group(1).trim() : null;
        if (actualLang == null || !lang.equalsIgnoreCase(actualLang)) {
            log.warn("云端转录语言不匹配: 请求={} 实际={}", lang, actualLang);
            return null;
        }
        String vtt = transcriptMarkdownToVtt(md);
        if (vtt == null) {
            return null;
        }
        log.info("云端转录字幕成功: {} {} ({} chars vtt)", videoId, lang, vtt.length());
        return new CaptionContent(lang, vtt);
    }

    /**
     * 把 "[m:ss] 文本"（空行分隔的段落）转录文本转换为 WebVTT。
     * 每段起始时间作为 cue 起点，下一段起始时间作为终点（末段固定 5 秒）。
     */
    static String transcriptMarkdownToVtt(String md) {
        int marker = md.indexOf("## Transcript");
        String body = marker >= 0 ? md.substring(marker + "## Transcript".length()) : md;
        int tail = body.indexOf("--- Generated by");
        if (tail >= 0) {
            body = body.substring(0, tail);
        }
        Pattern cue = Pattern.compile("(?m)^\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)\\]\\s*(.+?)\\s*$");
        List<String> starts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        Matcher m = cue.matcher(body);
        while (m.find()) {
            starts.add(vttTimestamp(m.group(1)));
            texts.add(m.group(2)
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
        }
        if (texts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("WEBVTT\n");
        for (int i = 0; i < texts.size(); i++) {
            String end = i + 1 < starts.size() ? starts.get(i + 1) : plusFiveSeconds(starts.get(i));
            sb.append("\n").append(starts.get(i)).append(" --> ").append(end).append("\n");
            sb.append(texts.get(i)).append("\n");
        }
        return sb.toString();
    }

    /** [h]:mm:ss 或 m:ss → HH:MM:SS.000 */
    private static String vttTimestamp(String t) {
        String[] p = t.split(":");
        int h = 0, min = 0, sec = 0;
        if (p.length == 3) {
            h = Integer.parseInt(p[0]);
            min = Integer.parseInt(p[1]);
            sec = Integer.parseInt(p[2]);
        } else if (p.length == 2) {
            min = Integer.parseInt(p[0]);
            sec = Integer.parseInt(p[1]);
        }
        return String.format("%02d:%02d:%02d.000", h, min, sec);
    }

    private static String plusFiveSeconds(String ts) {
        String[] a = ts.substring(0, 8).split(":");
        int total = Integer.parseInt(a[0]) * 3600 + Integer.parseInt(a[1]) * 60 + Integer.parseInt(a[2]) + 5;
        return String.format("%02d:%02d:%02d.000", total / 3600, (total % 3600) / 60, total % 60);
    }

    /**
     * 解析镜像渐进式流直链（formatId 形如 IV18/IV22，来自镜像解析结果）。
     * 实例未提供 formatStreams（companion 未就绪）时返回 null。
     */
    public String resolveStreamUrl(String url, String formatId) {
        if (formatId == null || !formatId.startsWith("IV")) {
            return null;
        }
        String videoId = extractVideoId(url);
        if (videoId == null) {
            return null;
        }
        String itag = formatId.substring(2);
        for (String base : instances) {
            String body = get(base + "/api/v1/videos/" + videoId);
            if (body == null || body.isBlank()) {
                continue;
            }
            try {
                for (JsonNode f : mapper.readTree(body).path("formatStreams")) {
                    if (itag.equals(f.path("itag").asText())) {
                        String u = f.path("url").asText(null);
                        if (u != null && !u.isBlank()) {
                            log.info("镜像流直链命中: {} itag={}", base, itag);
                            return u;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("镜像流解析失败 {}: {}", base, e.getMessage());
            }
        }
        return null;
    }

    // ===== 内部 =====

    /** 逐实例获取字幕轨道列表（轻量接口）；全部失败返回空列表 */
    private List<CaptionEntry> listCaptions(String videoId) {
        if (videoId == null) {
            return List.of();
        }
        for (String base : instances) {
            String body = get(base + "/api/v1/captions/" + videoId);
            if (body == null || body.isBlank()) {
                continue;
            }
            try {
                List<CaptionEntry> list = mapCaptions(mapper.readTree(body).path("captions"));
                if (!list.isEmpty()) {
                    log.info("镜像字幕轨道: {} -> {} 条 {}", base, list.size(),
                            captionLangs(list));
                    return list;
                }
            } catch (Exception e) {
                log.warn("镜像字幕列表解析失败 {}: {}", base, e.getMessage());
            }
        }
        return List.of();
    }

    /** 逐实例获取视频元数据（含字幕轨道与可选渐进式流）；全部失败返回 null */
    private MirrorMeta fetchMeta(String videoId) {
        for (String base : instances) {
            String body = get(base + "/api/v1/videos/" + videoId);
            if (body == null || body.isBlank()) {
                continue;
            }
            try {
                JsonNode j = mapper.readTree(body);
                String title = j.path("title").asText(null);
                if (title == null) {
                    continue;
                }
                long published = j.path("published").asLong(0);
                JsonNode thumbs = j.path("videoThumbnails");
                String thumb = thumbs.isArray() && !thumbs.isEmpty()
                        ? thumbs.get(0).path("url").asText(null) : null;
                Long duration = j.path("lengthSeconds").isNumber() ? j.path("lengthSeconds").asLong() : null;
                return new MirrorMeta(videoId, title, j.path("author").asText(null), thumb,
                        duration,
                        j.path("viewCount").isNumber() ? j.path("viewCount").asLong() : null,
                        published > 0 ? UPLOAD_FMT.format(Instant.ofEpochSecond(published).atZone(ZoneId.systemDefault())) : null,
                        mapFormatStreams(j.path("formatStreams")), mapCaptions(j.path("captions")));
            } catch (Exception e) {
                log.warn("镜像元数据解析失败 {}: {}", base, e.getMessage());
            }
        }
        return null;
    }

    /** oEmbed 兜底元数据（youtube.com 域，需代理可达），失败返回 null */
    private JsonNode fetchOEmbed(String videoId) {
        String body = get("https://www.youtube.com/oembed?url=https://youtu.be/" + videoId + "&format=json");
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private List<CaptionEntry> mapCaptions(JsonNode arr) {
        List<CaptionEntry> out = new ArrayList<>();
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode c : arr) {
            String path = c.path("url").asText(null);
            String lang = c.path("languageCode").asText(null);
            if (path != null && lang != null && !path.isBlank() && !lang.isBlank()) {
                out.add(new CaptionEntry(c.path("label").asText(lang), lang, path));
            }
        }
        return out;
    }

    /** 镜像渐进式流（音视频合一，可直接下载）；itag 用于构建 IV 前缀格式 ID */
    private List<FormatInfo> mapFormatStreams(JsonNode arr) {
        List<FormatInfo> out = new ArrayList<>();
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode f : arr) {
            String itag = f.path("itag").asText(null);
            if (itag == null || itag.isBlank()) {
                continue;
            }
            String ext = f.path("type").asText("").contains("webm") ? "webm" : "mp4";
            String quality = f.path("qualityLabel").asText(f.path("quality").asText("视频"));
            out.add(new FormatInfo("IV" + itag, ext, quality, null, null, null, null, null,
                    quality + " " + ext.toUpperCase(), false, false, true));
        }
        return out;
    }

    private List<String> captionLangs(List<CaptionEntry> captions) {
        return captions.stream()
                .map(CaptionEntry::lang)
                .distinct()
                .sorted(Comparator.comparingInt(l -> {
                    int i = LANG_PRIORITY.indexOf(l);
                    return i >= 0 ? i : LANG_PRIORITY.size();
                }))
                .toList();
    }

    /**
     * 双通道 GET：先直连（镜像实例多数可达），失败且配置了代理时再走代理
     * （f5.si 等实例对部分地区直连返回错误，经代理可达）。
     */
    private String get(String url) {
        return get(url, Duration.ofSeconds(8));
    }

    private String get(String url, Duration timeout) {
        String body = attempt(url, false, timeout);
        if (body != null) {
            return body;
        }
        if (proxy != null && !proxy.isBlank()) {
            return attempt(url, true, timeout);
        }
        return null;
    }

    private String attempt(String url, boolean viaProxy, Duration timeout) {
        try {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4));
            if (viaProxy) {
                URI pu = URI.create(proxy);
                InetSocketAddress addr = new InetSocketAddress(pu.getHost(),
                        pu.getPort() > 0 ? pu.getPort() : 80);
                if ("socks".equalsIgnoreCase(pu.getScheme())) {
                    b.proxy(new ProxySelector() {
                        @Override
                        public List<Proxy> select(URI uri) {
                            return List.of(new Proxy(Proxy.Type.SOCKS, addr));
                        }

                        @Override
                        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                        }
                    });
                } else {
                    b.proxy(ProxySelector.of(addr));
                }
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json,text/vtt,text/markdown,*/*")
                    .timeout(timeout)
                    .GET().build();
            HttpResponse<String> resp = b.build().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("镜像请求 {} 经{}返回 HTTP {}", url, viaProxy ? "代理" : "直连", resp.statusCode());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.debug("镜像请求失败 {} 经{}: {}", url, viaProxy ? "代理" : "直连", e.getMessage());
            return null;
        }
    }
}
