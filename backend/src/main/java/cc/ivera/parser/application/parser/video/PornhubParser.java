package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.AbstractVideoParser;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.infrastructure.service.YtDlpService;

import com.fasterxml.jackson.databind.JsonNode;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pornhub 专用适配层：复用 yt-dlp 获取真实流，并补齐站点格式中缺失的清晰度。
 */
@Service
public class PornhubParser extends AbstractVideoParser {

    private static final Pattern RESOLUTION_HEIGHT = Pattern.compile("\\d+x(\\d+)");
    private static final Pattern LABEL_HEIGHT = Pattern.compile("(\\d{3,4})p", Pattern.CASE_INSENSITIVE);

    private final YtDlpService ytDlp;

    public PornhubParser(YtDlpService ytDlp) {
        this.ytDlp = ytDlp;
    }

    @Override
    public Platform platform() {
        return Platform.PORNHUB;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    @Override
    public boolean supports(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            return host.equals("pornhub.com") || host.endsWith(".pornhub.com")
                    || host.equals("pornhub.net") || host.endsWith(".pornhub.net")
                    || host.equals("pornhub.org") || host.endsWith(".pornhub.org");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String cookies = ctx.cookies();
        return mapInfo(ytDlp.dumpInfo(url, cookies));
    }

    VideoInfo mapInfo(JsonNode info) {
        Map<Integer, FormatInfo> byHeight = new LinkedHashMap<>();
        List<FormatInfo> unknownHeight = new ArrayList<>();
        JsonNode rawFormats = info.path("formats");
        if (rawFormats.isArray()) {
            for (JsonNode raw : rawFormats) {
                FormatInfo format = mapFormat(raw);
                if (format == null) {
                    continue;
                }
                if (format.height() == null) {
                    unknownHeight.add(format);
                } else {
                    byHeight.merge(format.height(), format, PornhubParser::preferCombined);
                }
            }
        }

        List<FormatInfo> formats = new ArrayList<>();
        byHeight.keySet().stream().sorted(Comparator.reverseOrder())
                .forEach(height -> formats.add(byHeight.get(height)));
        formats.addAll(unknownHeight);
        if (formats.isEmpty()) {
            throw new BusinessException("未获取到 Pornhub 视频清晰度，请确认视频可播放并更新 yt-dlp 后重试");
        }

        long duration = info.path("duration").asLong(0);
        return new VideoInfo(
                info.path("id").asText(null),
                info.path("title").asText("未知标题"),
                info.path("thumbnail").asText(null),
                duration > 0 ? duration : null,
                duration > 0 ? YtDlpService.formatDuration(duration) : null,
                info.path("uploader").asText(null),
                "Pornhub",
                info.path("view_count").isNumber() ? info.path("view_count").asLong() : null,
                info.path("upload_date").asText(null),
                formats, null, List.of(), false);
    }

    private FormatInfo mapFormat(JsonNode raw) {
        String formatId = raw.path("format_id").asText("");
        String url = raw.path("url").asText("");
        String ext = raw.path("ext").asText("mp4");
        String protocol = raw.path("protocol").asText("");
        if (formatId.isBlank() || url.isBlank() || "mhtml".equals(protocol)
                || switch (ext.toLowerCase()) {
                    case "mhtml", "jpg", "jpeg", "png", "webp" -> true;
                    default -> false;
                }) {
            return null;
        }

        String vcodec = raw.path("vcodec").asText("none");
        String acodec = raw.path("acodec").asText("none");
        if ("none".equals(vcodec) && !"none".equals(acodec)) {
            return null;
        }

        Integer height = raw.path("height").isNumber() ? raw.path("height").asInt() : null;
        if (height == null) {
            for (String field : List.of("resolution", "format_note", "format", "format_id")) {
                height = extractHeight(raw.path(field).asText(null));
                if (height != null) {
                    break;
                }
            }
        }
        if (height == null && raw.path("quality").isNumber()) {
            int quality = raw.path("quality").asInt();
            if (quality >= 100 && quality <= 4320) {
                height = quality;
            }
        }
        if ("none".equals(vcodec) && "none".equals(acodec) && height == null) {
            return null;
        }

        Long filesize = raw.path("filesize").isNumber() ? raw.path("filesize").asLong() : null;
        Long filesizeApprox = raw.path("filesize_approx").isNumber()
                ? raw.path("filesize_approx").asLong() : null;
        String label = height != null ? height + "p " + ext.toUpperCase() : "默认 " + ext.toUpperCase();
        long size = filesize != null ? filesize : filesizeApprox != null ? filesizeApprox : 0;
        if (size > 0) {
            label += " (" + YtDlpService.humanSize(size) + ")";
        }
        return new FormatInfo(
                formatId, ext, raw.path("resolution").asText(null), height,
                filesize, filesizeApprox, "none".equals(vcodec) ? null : vcodec,
                "none".equals(acodec) ? null : acodec,
                label, !"none".equals(vcodec) && "none".equals(acodec), false, true);
    }

    private static FormatInfo preferCombined(FormatInfo current, FormatInfo candidate) {
        if (current.needsMerge() != candidate.needsMerge()) {
            return current.needsMerge() ? candidate : current;
        }
        return candidate;
    }

    private Integer extractHeight(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher resolution = RESOLUTION_HEIGHT.matcher(value);
        if (resolution.find()) {
            return Integer.parseInt(resolution.group(1));
        }
        Matcher label = LABEL_HEIGHT.matcher(value);
        return label.find() ? Integer.parseInt(label.group(1)) : null;
    }
}
