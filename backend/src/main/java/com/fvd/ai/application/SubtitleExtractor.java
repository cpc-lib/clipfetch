package com.fvd.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fvd.shared.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import com.fvd.ai.domain.SubtitleData;
import com.fvd.ai.infrastructure.VttParser;

/**
 * 从 yt-dlp JSON 中提取字幕：人工字幕优先于自动字幕，语言优先级 zh > en > ja > ko
 */
@Slf4j
@Service
public class SubtitleExtractor {

    private final String proxy;
    private final HttpClient plainClient;
    private final HttpClient proxyClient;

    private static final List<String> LANG_PRIORITY = List.of(
            "zh-Hans", "zh-CN", "zh-Hant", "zh-TW", "zh-HK", "zh", "en", "ja", "ko");

    public SubtitleExtractor(@Value("${app.proxy:}") String proxy) {
        this.proxy = proxy;
        this.plainClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        if (proxy != null && !proxy.isBlank()) {
            var uri = URI.create(proxy);
            this.proxyClient = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),
                            uri.getPort() > 0 ? uri.getPort() : 80)))
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } else {
            this.proxyClient = plainClient;
        }
    }

    /**
     * @return 字幕数据；无字幕返回 null
     */
    public SubtitleExtractorResult extract(JsonNode info) {
        if (info == null) {
            return null;
        }
        SubtitleExtractorResult result = tryTrackList(info.path("subtitles"), true);
        if (result == null) {
            result = tryTrackList(info.path("automatic_captions"), false);
        }
        if (result == null) {
            log.info("无可用字幕: {}", info.path("id").asText(""));
        }
        return result;
    }

    public record SubtitleExtractorResult(SubtitleData data, boolean manual) {
    }

    private SubtitleExtractorResult tryTrackList(JsonNode trackMap, boolean manual) {
        if (!trackMap.isObject() || trackMap.isEmpty()) {
            return null;
        }
        String lang = pickLang(trackMap);
        if (lang == null) {
            return null;
        }
        JsonNode tracks = trackMap.path(lang);
        if (!tracks.isArray() || tracks.isEmpty()) {
            return null;
        }
        String url = pickTrackUrl(tracks);
        if (url == null) {
            return null;
        }
        String text = downloadText(url);
        if (text == null || text.isBlank()) {
            return null;
        }
        List<SubtitleData.Segment> segments = VttParser.parse(text);
        if (segments.isEmpty()) {
            return null;
        }
        StringBuilder full = new StringBuilder();
        for (SubtitleData.Segment s : segments) {
            if (full.length() > 0) {
                full.append('\n');
            }
            full.append(s.text());
        }
        return new SubtitleExtractorResult(
                new SubtitleData(lang, segments, full.toString()), manual);
    }

    private String pickLang(JsonNode trackMap) {
        for (String lang : LANG_PRIORITY) {
            if (trackMap.has(lang) && trackMap.path(lang).isArray() && !trackMap.path(lang).isEmpty()) {
                return lang;
            }
        }
        // 兜底：任意 zh 开头
        var it = trackMap.fieldNames();
        while (it.hasNext()) {
            String lang = it.next();
            if (lang.startsWith("zh")) {
                return lang;
            }
        }
        // 再兜底：第一个非 live_chat 语言
        var it2 = trackMap.fieldNames();
        while (it2.hasNext()) {
            String lang = it2.next();
            if (!lang.contains("live_chat") && trackMap.path(lang).isArray() && !trackMap.path(lang).isEmpty()) {
                return lang;
            }
        }
        return null;
    }

    /**
     * 优先 vtt 格式的轨道
     */
    private String pickTrackUrl(JsonNode tracks) {
        String fallback = null;
        for (JsonNode t : tracks) {
            String ext = t.path("ext").asText("");
            String url = t.path("url").asText(null);
            if (url == null) {
                continue;
            }
            if (ext.equals("vtt")) {
                return url;
            }
            if (fallback == null) {
                fallback = url;
            }
        }
        return fallback;
    }

    private String downloadText(String url) {
        HttpClient client = url.contains("googlevideo") || url.contains("youtube.com")
                ? proxyClient : plainClient;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("下载字幕失败: {}", e.getMessage());
            return null;
        }
    }
}
