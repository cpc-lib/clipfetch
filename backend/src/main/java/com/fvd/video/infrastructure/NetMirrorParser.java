package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.FormatInfo;
import com.fvd.video.domain.VideoInfo;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetMirror（netmirror.center）电视剧解析。
 * <p>
 * 流程：
 * 1. {@code GET https://api2.imdb3.shop/api/tv/{id}} 取剧集元数据（标题、封面、dp 令牌、
 *    subjectid、上映年份、季集列表）。
 * 2. 取页面内联的 {@code window.SERVER_TIME} 作为时间戳。
 * 3. 签名 {@code sig = HMAC-SHA256_HEX("{tvId}:{serverTime}", "net###@@sss")}。
 * 4. 对每集构造 watchbox.php 播放器地址，拉取页面，从 {@code <div class="dl-item">} 中
 *    解析多清晰度 MP4 直链（带 CDN sign/t 时效令牌）。
 * <p>
 * formatId 形如 {@code se1ep1-1080}，编码季、集、高度，下载时重新解析该集取新鲜直链。
 */
@Slf4j
@Service
public class NetMirrorParser {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String META_API = "https://api2.imdb3.shop/api/tv/";
    private static final String PLAYER = "https://webvp.watch21.shop/play/watchbox.php";
    private static final String SIG_KEY = "net###@@sss";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern TV_ID = Pattern.compile("/tv/(\\d+)");
    private static final Pattern SERVER_TIME = Pattern.compile("SERVER_TIME\\s*=\\s*(\\d+)");
    private static final Pattern DL_ITEM = Pattern.compile(
            "<div class=\"dl-item\">\\s*([^<\\s]+)\\s+([\\d.]+)\\s*([KMGTP]?B)\\b.*?"
                    + "myFunction_dl\\('([^']+)'", Pattern.DOTALL);
    private static final Pattern HEIGHT = Pattern.compile("(\\d{3,4})p?", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private final int parseTimeout;
    private final HttpClient client;

    public NetMirrorParser(@Value("${app.parse-timeout:60}") int parseTimeout,
                           @Value("${app.proxy:}") String proxy) {
        this.parseTimeout = parseTimeout;
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

    public boolean supports(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.toLowerCase().contains("netmirror.");
        } catch (Exception e) {
            return false;
        }
    }

    public VideoInfo parse(String url) {
        String tvId = extractTvId(url);
        log.info("[NetMirror] 解析 tvId={}", tvId);
        JsonNode data = fetchMeta(tvId);
        String title = data.path("title").asText("NetMirror Video");
        String subjectid = data.path("subjectid").asText("");
        String dp = data.path("dp").asText("");
        String backdrop = data.path("backdrop_path").asText(null);
        String releaseDate = data.path("release_date").asText("");
        String year = extractYear(releaseDate);

        long serverTime = fetchServerTime(tvId);
        String sig = hmacSha256Hex(tvId + ":" + serverTime, SIG_KEY);
        String na = Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8));

        List<FormatInfo> formats = new ArrayList<>();
        JsonNode seasons = data.path("season");
        if (!seasons.isArray() || seasons.isEmpty()) {
            throw new BusinessException("未获取到剧集列表");
        }
        for (JsonNode s : seasons) {
            int se = s.path("se").asInt(1);
            int epCount = s.path("ep").asInt(0);
            if (epCount <= 0) {
                continue;
            }
            for (int ep = 1; ep <= epCount; ep++) {
                try {
                    String playerUrl = buildPlayerUrl(subjectid, se, ep, dp, na, year, serverTime, sig, tvId);
                    String html = fetch(playerUrl, "https://netmirror.center/");
                    List<Quality> quals = parseQualities(html);
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
        if (formats.isEmpty()) {
            throw new BusinessException("未获取到可下载的视频源，请稍后重试");
        }
        formats.sort(Comparator.comparing(FormatInfo::height,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new VideoInfo(tvId, title, backdrop, null, null,
                "NetMirror", "NetMirror", null, releaseDate, formats, null, List.of(), false);
    }

    /**
     * 根据 formatId 重新解析该集，返回对应清晰度的 MP4 直链。
     */
    public String resolveDownloadUrl(String url, String formatId) {
        String tvId = extractTvId(url);
        Matcher fm = Pattern.compile("se(\\d+)ep(\\d+)-(\\d+)").matcher(formatId == null ? "" : formatId);
        if (!fm.find()) {
            throw new BusinessException("无效的清晰度标识: " + formatId);
        }
        int se = Integer.parseInt(fm.group(1));
        int ep = Integer.parseInt(fm.group(2));
        int height = Integer.parseInt(fm.group(3));

        JsonNode data = fetchMeta(tvId);
        String title = data.path("title").asText("NetMirror Video");
        String subjectid = data.path("subjectid").asText("");
        String dp = data.path("dp").asText("");
        String year = extractYear(data.path("release_date").asText(""));
        long serverTime = fetchServerTime(tvId);
        String sig = hmacSha256Hex(tvId + ":" + serverTime, SIG_KEY);
        String na = Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8));

        String playerUrl = buildPlayerUrl(subjectid, se, ep, dp, na, year, serverTime, sig, tvId);
        String html = fetch(playerUrl, "https://netmirror.center/");
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

    private String extractTvId(String url) {
        Matcher m = TV_ID.matcher(url);
        if (!m.find()) {
            throw new BusinessException("无法从链接中解析 NetMirror 视频 ID");
        }
        return m.group(1);
    }

    private JsonNode fetchMeta(String tvId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(META_API + tvId))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", "https://netmirror.center/")
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

    private long fetchServerTime(String tvId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://netmirror.center/tv/" + tvId + "/?embed=1"))
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

    private record Quality(String label, Integer height, long sizeBytes, String sizeText, String url) {
    }
}
