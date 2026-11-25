package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetMirror（netmirror.gold）电视剧/电影解析。
 * <p>
 * 流程：
 * 1. {@code GET https://api2.imdb3.shop/api/tv/{id}} 取元数据（标题、封面、dp 令牌、
 *    subjectid、上映年份、季集列表；电影无 season 字段）。
 * 2. 取页面内联的 {@code window.SERVER_TIME} 作为时间戳（/tv/ 或 /movie/ embed 页）。
 * 3. 签名 {@code sig = HMAC-SHA256_HEX("{tvId}:{serverTime}", "net###@@sss")}。
 * 4. 电视剧对每集构造 watchbox.php 播放器地址；电影按 se=0&ep=0 单集处理。
 *    拉取页面后从 {@code <div class="dl-item">} 中解析多清晰度 MP4 直链（带 CDN sign/t 时效令牌）。
 * <p>
 * formatId 形如 {@code se1ep1-1080}（电影为 {@code se0ep0-1080}），编码季、集、高度，
 * 下载时重新解析取新鲜直链。
 */
@Slf4j
@Service
public class NetMirrorParser extends AbstractVideoParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String META_API = "https://api2.imdb3.shop/api/tv/";
    private static final String PLAYER = "https://webvp.watch21.shop/play/watchbox.php";
    private static final String TMDB_API = "https://tmdb.imdb3.shop/3/";
    private static final String TMDB_IMG = "https://image.tmdb.org/t/p/original";
    private static final String EXTERNAL_PLAYER = "https://webvp.watch21.shop/play/external.php";
    private static final String SIG_KEY = "net###@@sss";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern CONTENT_ID = Pattern.compile("/(?:tv|movie)/(\\d+)");
    private static final Pattern SERVER_TIME = Pattern.compile("SERVER_TIME\\s*=\\s*(\\d+)");
    private static final Pattern DL_ITEM = Pattern.compile(
            "<div class=\"dl-item\">\\s*([^<\\s]+)\\s+([\\d.]+)\\s*([KMGTP]?B)\\b.*?"
                    + "myFunction_dl\\('([^']+)'", Pattern.DOTALL);
    private static final Pattern SERVERS = Pattern.compile("const servers = (\\[.*?\\]);", Pattern.DOTALL);
    private static final Pattern QUALITIES = Pattern.compile("const qualities = (\\[.*?\\]);", Pattern.DOTALL);
    private static final Pattern MP4_LABEL = Pattern.compile("Mp4-(.+)-(\\d{3,4})p", Pattern.CASE_INSENSITIVE);
    private static final Pattern TMDB_FORMAT = Pattern.compile("se(\\d+)ep(\\d+)-(\\d+)(?:-(.+))?");
    private static final Pattern HEIGHT = Pattern.compile("(\\d{3,4})p?", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private final int parseTimeout;
    private final HttpClient client;
    private final NetMirrorTmdbKeyService tmdbKeyService;

    public NetMirrorParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                           @Value("${app.proxy:}") String proxy,
                           NetMirrorTmdbKeyService tmdbKeyService) {
        this.parseTimeout = parseTimeout;
        this.tmdbKeyService = tmdbKeyService;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(parseTimeout))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null && !proxy.isBlank()) {
            URI u = URI.create(proxy);
            if (u.getHost() != null && u.getPort() > 0) {
                b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort())));
            }
        }
        this.client = b.build();
    }

    @Override
    public Platform platform() {
        return Platform.NETMIRROR;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    @Override
    public boolean supports(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.toLowerCase().contains("netmirror.");
        } catch (Exception e) {
            return false;
        }
    }

    /** 新版 tmdb 路径：{@code /tmdb/watch/{tv|movie}/{tmdbId}}，走 TMDB 元数据与 external.php 播放器 */
    public boolean isTmdbWatch(String url) {
        return url != null && url.contains("/tmdb/watch/");
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        if (isTmdbWatch(url)) {
            return parseTmdb(url);
        }
        String tvId = extractTvId(url);
        log.info("[NetMirror] 解析 tvId={}", tvId);
        JsonNode data = fetchMeta(tvId);
        String tmId = data.path("tm_id").asText(null);
        if (tmId != null && !tmId.isBlank() && !"null".equalsIgnoreCase(tmId)) {
            try {
                return parseTmdbByMeta(url, tvId, tmId, data);
            } catch (Exception e) {
                log.warn("[NetMirror] TMDB 多源解析失败，回退 watchbox: {}", safeMsg(e));
            }
        }
        String title = data.path("title").asText("NetMirror Video");
        String subjectid = data.path("subjectid").asText("");
        String dp = data.path("dp").asText("");
        String backdrop = data.path("backdrop_path").asText(null);
        String releaseDate = data.path("release_date").asText("");
        String year = extractYear(releaseDate);

        long serverTime = fetchServerTime(tvId, isMovie(url));
        String sig = hmacSha256Hex(tvId + ":" + serverTime, SIG_KEY);
        String na = Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8));

        List<FormatInfo> formats = new ArrayList<>();
        JsonNode seasons = data.path("season");
        // 电影的 season 可能为 [{se:0, ep:0}]（非空但无集），需同时判断是否有有效集数
        boolean hasEpisodes = false;
        if (seasons.isArray()) {
            for (JsonNode s : seasons) {
                if (s.path("ep").asInt(0) > 0) { hasEpisodes = true; break; }
            }
        }
        if (!seasons.isArray() || seasons.isEmpty() || !hasEpisodes) {
            // 电影：无 season 结构或无有效集数，按单集处理（se=0&ep=0）
            try {
                String playerUrl = buildPlayerUrl(subjectid, 0, 0, dp, na, year, serverTime, sig, tvId);
                String html = fetch(playerUrl, tmdbKeyService.getSiteReferer());
                List<Quality> quals = parseQualities(html);
                if (quals.isEmpty()) {
                    log.warn("[NetMirror] 电影无可用清晰度: tvId={} pageLen={} 片段={}", tvId,
                            html.length(), abbreviate(html));
                }
                for (Quality q : quals) {
                    String fid = String.format("se0ep0-%d", q.height);
                    String label = String.format("%s (%s)", q.label, q.sizeText);
                    formats.add(new FormatInfo(fid, "mp4", q.label + "p", q.height,
                            q.sizeBytes, null, null, null, label, false, false, true));
                }
                log.info("[NetMirror] 电影解析到 {} 个清晰度", quals.size());
            } catch (Exception e) {
                log.warn("[NetMirror] 电影解析失败: {}", e.getMessage());
            }
        } else {
            for (JsonNode s : seasons) {
                int se = s.path("se").asInt(1);
                int epCount = s.path("ep").asInt(0);
                if (epCount <= 0) {
                    continue;
                }
                for (int ep = 1; ep <= epCount; ep++) {
                    try {
                        String playerUrl = buildPlayerUrl(subjectid, se, ep, dp, na, year, serverTime, sig, tvId);
                        String html = fetch(playerUrl, tmdbKeyService.getSiteReferer());
                        List<Quality> quals = parseQualities(html);
                        if (quals.isEmpty()) {
                            log.warn("[NetMirror] S{}E{} 无可用清晰度: tvId={} pageLen={} 片段={}",
                                    se, ep, tvId, html.length(), abbreviate(html));
                        }
                        for (Quality q : quals) {
                            String fid = String.format("se%dep%d-%d", se, ep, q.height);
                            String label = String.format("S%dE%d · %s (%s)", se, ep, q.label, q.sizeText);
                            formats.add(new FormatInfo(fid, "mp4", q.label + "p", q.height,
                                    q.sizeBytes, null, null, null, label, false, false, true));
                        }
                        log.info("[NetMirror] S{}E{} 解析到 {} 个清晰度", se, ep, quals.size());
                    } catch (Exception e) {
                        log.warn("[NetMirror] S{}E{} 解析失败: {}", se, ep, e.getMessage());
                    }
                }
            }
        }
        if (formats.isEmpty()) {
            throw new BusinessException("未获取到可下载的视频源，请稍后重试");
        }
        formats.sort(NetMirrorParser::compareFormats);
        return new VideoInfo(tvId, title, backdrop, null, null,
                "NetMirror", "NetMirror", null, releaseDate, formats, null, List.of(), false);
    }

    /**
     * 根据 formatId 重新解析该集，返回对应清晰度的 MP4 直链。
     */
    public String resolveDownloadUrl(String url, String formatId) {
        if (isTmdbWatch(url)) {
            return resolveTmdb(url, formatId);
        }
        String tvId = extractTvId(url);
        JsonNode data = fetchMeta(tvId);
        String tmId = data.path("tm_id").asText(null);
        if (tmId != null && !tmId.isBlank() && !"null".equalsIgnoreCase(tmId)) {
            try {
                return resolveTmdbByMeta(url, tmId, formatId, tvId);
            } catch (Exception e) {
                log.warn("[NetMirror] TMDB 多源解析失败，回退 watchbox: {}", safeMsg(e));
            }
        }
        Matcher fm = Pattern.compile("se(\\d+)ep(\\d+)-(\\d+)").matcher(formatId == null ? "" : formatId);
        if (!fm.find()) {
            throw new BusinessException("无效的清晰度标识: " + formatId);
        }
        int se = Integer.parseInt(fm.group(1));
        int ep = Integer.parseInt(fm.group(2));
        int height = Integer.parseInt(fm.group(3));

        String title = data.path("title").asText("NetMirror Video");
        String subjectid = data.path("subjectid").asText("");
        String dp = data.path("dp").asText("");
        String year = extractYear(data.path("release_date").asText(""));
        long serverTime = fetchServerTime(tvId, isMovie(url));
        String sig = hmacSha256Hex(tvId + ":" + serverTime, SIG_KEY);
        String na = Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8));

        String playerUrl = buildPlayerUrl(subjectid, se, ep, dp, na, year, serverTime, sig, tvId);
        String html = fetch(playerUrl, tmdbKeyService.getSiteReferer());
        List<Quality> quals = parseQualities(html);
        if (quals.isEmpty()) {
            throw new BusinessException("未获取到该集视频源，请稍后重试");
        }
        return quals.stream()
                .filter(q -> q.height == height)
                .findFirst()
                .orElse(quals.get(0))
                .url;
    }

    // ==========================================
    // 新版 tmdb 路径（/tmdb/watch/{type}/{id}）
    // ==========================================

    private VideoInfo parseTmdb(String url) {
        String id = extractTvId(url);
        boolean movie = isMovie(url);
        String type = movie ? "movie" : "tv";
        log.info("[NetMirror] TMDB 解析 type={} id={}", type, id);

        JsonNode data = fetchTmdbMeta(type, id);
        String title = data.path(movie ? "title" : "name").asText("NetMirror Video");
        String backdropPath = data.path("backdrop_path").asText("");
        String backdrop = backdropPath.isBlank() ? null : TMDB_IMG + backdropPath;
        String releaseDate = data.path(movie ? "release_date" : "first_air_date").asText("");

        long serverTime = fetchServerTime(
                tmdbKeyService.getSiteOrigin() + "/tmdb/watch/" + type + "/" + id + "?embed=1");
        String sig = hmacSha256Hex(id + ":" + serverTime, SIG_KEY);

        List<FormatInfo> formats = new ArrayList<>();
        if (movie) {
            formats.addAll(fetchTmdbFormatsForEpisode(id, "movie", 0, 0, serverTime, sig));
        } else {
            List<EpisodeTarget> targets = new ArrayList<>();
            JsonNode seasons = data.path("seasons");
            for (JsonNode s : seasons) {
                int se = s.path("season_number").asInt(0);
                int epCount = s.path("episode_count").asInt(0);
                if (se <= 0 || epCount <= 0) {
                    continue;
                }
                for (int ep = 1; ep <= epCount; ep++) {
                    targets.add(new EpisodeTarget(se, ep));
                }
            }
            if (targets.size() <= 1) {
                for (EpisodeTarget t : targets) {
                    formats.addAll(fetchTmdbFormatsForEpisode(id, "tv", t.se, t.ep, serverTime, sig));
                }
            } else {
                int parallelism = Math.min(6, targets.size());
                ExecutorService pool = Executors.newFixedThreadPool(parallelism);
                try {
                    List<CompletableFuture<List<FormatInfo>>> futures = targets.stream()
                            .map(t -> CompletableFuture.supplyAsync(
                                    () -> fetchTmdbFormatsForEpisode(id, "tv", t.se, t.ep, serverTime, sig), pool))
                            .toList();
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    for (CompletableFuture<List<FormatInfo>> f : futures) {
                        formats.addAll(f.join());
                    }
                } finally {
                    pool.shutdown();
                }
            }
        }
        if (formats.isEmpty()) {
            throw new BusinessException("未获取到可下载的视频源，请稍后重试");
        }
        formats.sort(NetMirrorParser::compareFormats);
        return new VideoInfo(id, title, backdrop, null, null,
                "NetMirror", "NetMirror", null, releaseDate, formats, null, List.of(), false);
    }

    private static final Pattern FORMAT_SE_EP = Pattern.compile("se(\\d+)ep(\\d+)-(\\d+)");

    private record EpisodeTarget(int se, int ep) {}

    public static int compareFormats(FormatInfo a, FormatInfo b) {
        String fida = a != null && a.formatId() != null ? a.formatId() : "";
        String fidb = b != null && b.formatId() != null ? b.formatId() : "";
        Matcher ma = FORMAT_SE_EP.matcher(fida);
        Matcher mb = FORMAT_SE_EP.matcher(fidb);
        if (ma.find() && mb.find()) {
            int seA = Integer.parseInt(ma.group(1));
            int seB = Integer.parseInt(mb.group(1));
            if (seA != seB) {
                return Integer.compare(seA, seB);
            }
            int epA = Integer.parseInt(ma.group(2));
            int epB = Integer.parseInt(mb.group(2));
            if (epA != epB) {
                return Integer.compare(epA, epB);
            }
            int hA = Integer.parseInt(ma.group(3));
            int hB = Integer.parseInt(mb.group(3));
            if (hA != hB) {
                return Integer.compare(hB, hA); // 高清优先
            }
            return fida.compareTo(fidb);
        }
        int hA = a != null && a.height() != null ? a.height() : 0;
        int hB = b != null && b.height() != null ? b.height() : 0;
        if (hA != hB) {
            return Integer.compare(hB, hA);
        }
        return fida.compareTo(fidb);
    }

    private List<FormatInfo> fetchTmdbFormatsForEpisode(String id, String type, int se, int ep,
                                                        long serverTime, String sig) {
        String where = "movie".equals(type) ? "电影" : "S" + se + "E" + ep;
        List<FormatInfo> res = new ArrayList<>();
        try {
            List<TmdbQuality> quals = fetchTmdbQualities(id, type, se, ep, serverTime, sig);
            if (quals.isEmpty()) {
                log.warn("[NetMirror] {} 无可用清晰度: id={}", where, id);
                return res;
            }
            for (TmdbQuality q : quals) {
                String fid = String.format("se%dep%d-%d-%s", se, ep, q.height, slug(q.lang));
                String label = "movie".equals(type)
                        ? String.format("%s %dp", q.lang, q.height)
                        : String.format("S%dE%d · %s %dp", se, ep, q.lang, q.height);
                res.add(new FormatInfo(fid, "mp4", q.height + "p", q.height,
                        null, null, null, null, label, false, false, true));
            }
            log.info("[NetMirror] {} 解析到 {} 个清晰度", where, quals.size());
        } catch (Exception e) {
            log.warn("[NetMirror] {} 解析失败: {}", where, safeMsg(e));
        }
        return res;
    }

    private VideoInfo parseTmdbByMeta(String pageUrl, String tvId, String tmId, JsonNode data) {
        String title = data.path("title").asText("NetMirror Video");
        String backdropPath = data.path("backdrop_path").asText("");
        String backdrop = backdropPath.isBlank() ? null : backdropPath;
        String releaseDate = data.path("release_date").asText("");
        boolean movie = isMovie(pageUrl);
        String type = movie ? "movie" : "tv";

        long serverTime = fetchServerTime(tvId, movie);
        String sig = hmacSha256Hex(tmId + ":" + serverTime, SIG_KEY);

        List<FormatInfo> formats = new ArrayList<>();
        JsonNode seasons = data.path("season");
        boolean hasEpisodes = false;
        if (seasons.isArray()) {
            for (JsonNode s : seasons) {
                if (s.path("ep").asInt(0) > 0) {
                    hasEpisodes = true;
                    break;
                }
            }
        }

        if (movie || !seasons.isArray() || seasons.isEmpty() || !hasEpisodes) {
            formats.addAll(fetchTmdbFormatsForEpisode(tmId, "movie", 0, 0, serverTime, sig));
        } else {
            List<EpisodeTarget> targets = new ArrayList<>();
            for (JsonNode s : seasons) {
                int se = s.path("se").asInt(1);
                int epCount = s.path("ep").asInt(0);
                if (epCount <= 0) {
                    continue;
                }
                for (int ep = 1; ep <= epCount; ep++) {
                    targets.add(new EpisodeTarget(se, ep));
                }
            }
            if (targets.size() <= 1) {
                for (EpisodeTarget t : targets) {
                    formats.addAll(fetchTmdbFormatsForEpisode(tmId, "tv", t.se, t.ep, serverTime, sig));
                }
            } else {
                int parallelism = Math.min(6, targets.size());
                ExecutorService pool = Executors.newFixedThreadPool(parallelism);
                try {
                    List<CompletableFuture<List<FormatInfo>>> futures = targets.stream()
                            .map(t -> CompletableFuture.supplyAsync(
                                    () -> fetchTmdbFormatsForEpisode(tmId, "tv", t.se, t.ep, serverTime, sig), pool))
                            .toList();
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    for (CompletableFuture<List<FormatInfo>> f : futures) {
                        formats.addAll(f.join());
                    }
                } finally {
                    pool.shutdown();
                }
            }
        }

        if (formats.isEmpty()) {
            throw new BusinessException("未获取到可下载或播放的视频源");
        }
        formats.sort(NetMirrorParser::compareFormats);
        return new VideoInfo(tvId, title, backdrop, null, null,
                "NetMirror", "NetMirror", null, releaseDate, formats, null, List.of(), false);
    }

    private String resolveTmdbByMeta(String url, String tmId, String formatId, String tvId) {
        String type = isMovie(url) ? "movie" : "tv";
        Matcher fm = TMDB_FORMAT.matcher(formatId == null ? "" : formatId);
        int se = 0;
        int ep = 0;
        int height = 1080;
        String lang = "";
        if (fm.find()) {
            se = Integer.parseInt(fm.group(1));
            ep = Integer.parseInt(fm.group(2));
            height = Integer.parseInt(fm.group(3));
            lang = fm.group(4) != null ? fm.group(4) : "";
        } else {
            Matcher oldFm = Pattern.compile("se(\\d+)ep(\\d+)-(\\d+)").matcher(formatId == null ? "" : formatId);
            if (oldFm.find()) {
                se = Integer.parseInt(oldFm.group(1));
                ep = Integer.parseInt(oldFm.group(2));
                height = Integer.parseInt(oldFm.group(3));
            } else {
                throw new BusinessException("无效的清晰度标识: " + formatId);
            }
        }

        long serverTime = fetchServerTime(tvId != null ? tvId : tmId, "movie".equals(type));
        String sig = hmacSha256Hex(tmId + ":" + serverTime, SIG_KEY);
        List<TmdbQuality> quals = fetchTmdbQualities(tmId, type, se, ep, serverTime, sig);
        if (quals.isEmpty()) {
            throw new BusinessException("未获取到该集视频源，请稍后重试");
        }
        final String finalLang = lang;
        final int finalHeight = height;
        return quals.stream()
                .filter(q -> q.height == finalHeight && (finalLang.isBlank() || slug(q.lang).equals(finalLang)))
                .findFirst()
                .orElseGet(() -> quals.stream()
                        .filter(q -> q.height == finalHeight)
                        .findFirst()
                        .orElse(quals.get(0)))
                .url;
    }

    private String resolveTmdb(String url, String formatId) {
        String id = extractTvId(url);
        String type = isMovie(url) ? "movie" : "tv";
        Matcher fm = TMDB_FORMAT.matcher(formatId == null ? "" : formatId);
        if (!fm.find()) {
            throw new BusinessException("无效的清晰度标识: " + formatId);
        }
        int se = Integer.parseInt(fm.group(1));
        int ep = Integer.parseInt(fm.group(2));
        int height = Integer.parseInt(fm.group(3));
        String lang = fm.group(4);

        long serverTime = fetchServerTime(
                tmdbKeyService.getSiteOrigin() + "/tmdb/watch/" + type + "/" + id + "?embed=1");
        String sig = hmacSha256Hex(id + ":" + serverTime, SIG_KEY);
        List<TmdbQuality> quals = fetchTmdbQualities(id, type, se, ep, serverTime, sig);
        if (quals.isEmpty()) {
            throw new BusinessException("未获取到该集视频源，请稍后重试");
        }
        return quals.stream()
                .filter(q -> q.height == height && slug(q.lang).equals(lang))
                .findFirst()
                .orElseGet(() -> quals.stream()
                        .filter(q -> q.height == height)
                        .findFirst()
                        .orElse(quals.get(0)))
                .url;
    }

    /** external.php 返回多个服务器，取 watchcinemo.php 并解析其中的 mp4 清晰度。 */
    private List<TmdbQuality> fetchTmdbQualities(String id, String type, int se, int ep,
                                                 long serverTime, String sig) {
        StringBuilder url = new StringBuilder(EXTERNAL_PLAYER).append("?tm_id=").append(id);
        if ("tv".equals(type)) {
            url.append("&se=").append(se).append("&ep=").append(ep);
        }
        url.append("&tm_type=").append(type)
                .append("&ts=").append(serverTime)
                .append("&sig=").append(sig)
                .append("&exten=true");
        String html = fetch(url.toString(), tmdbKeyService.getSiteReferer());
        Matcher sm = SERVERS.matcher(html);
        if (!sm.find()) {
            throw new BusinessException("NetMirror 播放器页未返回可用服务器");
        }
        JsonNode servers;
        try {
            servers = MAPPER.readTree(sm.group(1));
        } catch (Exception e) {
            throw new BusinessException("NetMirror 服务器列表解析失败：" + safeMsg(e));
        }
        String serverUrl = null;
        for (JsonNode s : servers) {
            String u = s.path("url").asText("");
            if (u.contains("watchcinemo.php")) {
                serverUrl = u;
                break;
            }
        }
        if (serverUrl == null) {
            throw new BusinessException("NetMirror 播放器未提供可下载的服务器");
        }
        String playerHtml = fetch(serverUrl, EXTERNAL_PLAYER);
        return parseTmdbQualities(playerHtml);
    }

    private static List<TmdbQuality> parseTmdbQualities(String html) {
        Matcher qm = QUALITIES.matcher(html);
        if (!qm.find()) {
            return List.of();
        }
        JsonNode arr;
        try {
            arr = MAPPER.readTree(qm.group(1));
        } catch (Exception e) {
            return List.of();
        }
        List<TmdbQuality> list = new ArrayList<>();
        for (JsonNode q : arr) {
            String type = q.path("type").asText("");
            String label = q.path("html").asText("");
            String url = q.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            if ("mp4".equalsIgnoreCase(type)) {
                Matcher lm = MP4_LABEL.matcher(label);
                if (lm.find()) {
                    list.add(new TmdbQuality(lm.group(1).trim(), Integer.parseInt(lm.group(2)), url));
                }
            } else if ("dash".equalsIgnoreCase(type)) {
                Matcher hm = HEIGHT.matcher(q.path("quality").asText(label));
                int h = hm.find() ? Integer.parseInt(hm.group(1)) : 1080;
                String lang = label.replaceFirst("(?i)^Dash-?", "").replaceFirst("(?i)-\\d+p?$", "").trim();
                if (lang.isBlank()) {
                    lang = "Original";
                }
                list.add(new TmdbQuality(lang, h, url));
            }
        }
        return list;
    }

    private JsonNode fetchTmdbMeta(String type, String id) {
        List<String> keys = tmdbKeyService.getKeys();
        TmdbMetaAttempt first = tryFetchTmdbMeta(type, id, keys);
        if (first.data != null) {
            return first.data;
        }
        if (first.authFailed) {
            log.warn("[NetMirror] 库中 TMDB Key 全部鉴权失效，触发自动刷新并重试: type={} id={}", type, id);
            List<String> refreshed = tmdbKeyService.refreshOnInvalid(keys);
            TmdbMetaAttempt second = tryFetchTmdbMeta(type, id, refreshed);
            if (second.data != null) {
                return second.data;
            }
            throw new BusinessException("NetMirror TMDB 元数据获取失败（已自动刷新 Key）："
                    + (second.lastError == null ? "无可用接口密钥" : safeMsg(second.lastError)));
        }
        throw new BusinessException("NetMirror TMDB 元数据获取失败："
                + (first.lastError == null ? "无可用接口密钥" : safeMsg(first.lastError)));
    }

    private TmdbMetaAttempt tryFetchTmdbMeta(String type, String id, List<String> keys) {
        Exception last = null;
        boolean authFailed = false;
        for (String key : keys) {
            try {
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create(TMDB_API + type + "/" + id + "?api_key=" + key))
                        .timeout(Duration.ofSeconds(parseTimeout))
                        .header("User-Agent", UA)
                        .GET().build();
                HttpResponse<String> resp = client.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                if (code == 200) {
                    return new TmdbMetaAttempt(MAPPER.readTree(resp.body()), false, null);
                }
                if (code == 404) {
                    throw new BusinessException("NetMirror TMDB 未找到对应影片元数据");
                }
                if (code == 401 || code == 403) {
                    authFailed = true;
                    log.warn("[NetMirror] TMDB Key {} 鉴权失败: HTTP {}",
                            NetMirrorTmdbKeyService.maskKey(key), code);
                } else if (code == 429) {
                    log.warn("[NetMirror] TMDB Key {} 触发限流: HTTP 429，切换下一把 Key",
                            NetMirrorTmdbKeyService.maskKey(key));
                }
                last = new BusinessException("NetMirror TMDB 元数据接口返回 HTTP " + code);
            } catch (BusinessException e) {
                if ("NetMirror TMDB 未找到对应影片元数据".equals(e.getMessage())) {
                    throw e;
                }
                last = e;
            } catch (Exception e) {
                log.warn("[NetMirror] TMDB Key {} 请求异常: {}",
                        NetMirrorTmdbKeyService.maskKey(key), safeMsg(e));
                last = e;
            }
        }
        return new TmdbMetaAttempt(null, authFailed, last);
    }

    private record TmdbMetaAttempt(JsonNode data, boolean authFailed, Exception lastError) {
    }

    private static String slug(String lang) {
        return lang == null ? "" : lang.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private record TmdbQuality(String lang, int height, String url) {
    }

    private String extractTvId(String url) {
        Matcher m = CONTENT_ID.matcher(url);
        if (!m.find()) {
            throw new BusinessException("无法从链接中解析 NetMirror 视频 ID");
        }
        return m.group(1);
    }

    private static boolean isMovie(String url) {
        return url != null && url.contains("/movie/");
    }

    private JsonNode fetchMeta(String tvId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(META_API + tvId))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", tmdbKeyService.getSiteReferer())
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("NetMirror 元数据接口返回 HTTP " + resp.statusCode());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                return results.get(0);
            }
            return root;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("NetMirror 元数据获取失败：" + safeMsg(e));
        }
    }

    private long fetchServerTime(String tvId, boolean movie) {
        return fetchServerTime(tmdbKeyService.getSiteOrigin() + "/" + (movie ? "movie" : "tv") + "/" + tvId + "/?embed=1");
    }

    private long fetchServerTime(String embedUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(embedUrl))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher m = SERVER_TIME.matcher(resp.body());
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        } catch (Exception e) {
            log.warn("[NetMirror] 获取 SERVER_TIME 失败，回退本地时间: {}", safeMsg(e));
        }
        return System.currentTimeMillis() / 1000;
    }

    private String buildPlayerUrl(String subjectid, int se, int ep, String dp, String na,
                                  String year, long ts, String sig, String tvId) {
        StringBuilder qs = new StringBuilder(PLAYER).append('?');
        qs.append("id=").append(enc(subjectid));
        qs.append("&se=").append(se);
        qs.append("&ep=").append(ep);
        qs.append("&dp=").append(enc(dp));
        qs.append("&na=").append(enc(na));
        qs.append("&year=").append(enc(year));
        qs.append("&tm_id=");
        qs.append("&ts=").append(ts);
        qs.append("&sig=").append(sig);
        qs.append("&nid=").append(tvId);
        qs.append("&exten=");
        qs.append("&tv=");
        qs.append("&token=");
        return qs.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String fetch(String url, String referer) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .GET();
            if (referer != null && !referer.isBlank()) {
                rb.header("Referer", referer);
            }
            HttpResponse<String> resp = client.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("播放器页面返回 HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("NetMirror 播放器页面请求失败：" + safeMsg(e));
        }
    }

    private static List<Quality> parseQualities(String html) {
        List<Quality> list = new ArrayList<>();
        Matcher m = DL_ITEM.matcher(html);
        while (m.find()) {
            String label = m.group(1).trim();
            String sizeNum = m.group(2);
            String sizeUnit = m.group(3);
            String url = m.group(4);
            Integer height = null;
            Matcher hm = HEIGHT.matcher(label);
            if (hm.find()) {
                height = Integer.parseInt(hm.group(1));
            }
            list.add(new Quality(label, height, parseSize(sizeNum, sizeUnit),
                    sizeNum + sizeUnit, url));
        }
        return list;
    }

    private static long parseSize(String num, String unit) {
        try {
            double n = Double.parseDouble(num);
            long mult = switch (unit.toUpperCase()) {
                case "KB" -> 1024L;
                case "MB" -> 1024L * 1024;
                case "GB" -> 1024L * 1024 * 1024;
                case "TB" -> 1024L * 1024 * 1024 * 1024;
                default -> 1L;
            };
            return (long) (n * mult);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractYear(String releaseDate) {
        if (releaseDate == null) {
            return "";
        }
        Matcher m = YEAR.matcher(releaseDate);
        return m.find() ? m.group() : "";
    }

    private static String hmacSha256Hex(String message, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new BusinessException("签名计算失败：" + safeMsg(e));
        }
    }

    private static String safeMsg(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** 页面内容压缩为单行摘要（供无清晰度时排查用） */
    private static String abbreviate(String html) {
        if (html == null) {
            return "";
        }
        String s = html.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private record Quality(String label, Integer height, long sizeBytes, String sizeText, String url) {
    }
}
