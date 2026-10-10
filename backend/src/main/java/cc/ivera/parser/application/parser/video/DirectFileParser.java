package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.application.ParseContext;
import cc.ivera.parser.application.parser.CookiePolicy;
import cc.ivera.parser.application.parser.VideoParser;
import cc.ivera.parser.domain.FormatInfo;
import cc.ivera.parser.domain.Platform;
import cc.ivera.parser.domain.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 直链文件解析：URL 路径直接指向媒体文件（.png/.jpg/.mp3/.mp4 等）。
 * 这类链接无需页面解析，返回单一"原始文件"格式；下载时服务端流式转发源文件。
 */
@Slf4j
@Component
public class DirectFileParser implements VideoParser {

    private static final Pattern MEDIA_EXT = Pattern.compile(
            "\\.(png|jpe?g|gif|webp|bmp|svg|avif|heic"
                    + "|mp3|flac|m4a|aac|ogg|wav|opus"
                    + "|mp4|mkv|webm|mov|avi|ts|flv|m4v|wmv)$");

    private static final Set<String> AUDIO_EXT = Set.of(
            "mp3", "flac", "m4a", "aac", "ogg", "wav", "opus");

    private static final Set<String> IMAGE_EXT = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "heic");

    @Override
    public Platform platform() {
        return Platform.OTHER;
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }

    @Override
    public boolean supports(String url) {
        try {
            String path = URI.create(url).getPath();
            return path != null && MEDIA_EXT.matcher(path.toLowerCase(Locale.ROOT)).find();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VideoInfo parse(ParseContext ctx) {
        String url = ctx.url();
        String filename = filenameFromUrl(url);
        String ext = extOf(url);
        boolean audio = ext != null && AUDIO_EXT.contains(ext);
        boolean image = ext != null && IMAGE_EXT.contains(ext);
        // 图片直链封面直接显示图片本身；音频/视频无缩略图，前端回退默认壁纸
        String thumbnail = image ? url : null;
        log.info("[DirectFile] 直链文件解析: filename={}, ext={}, url={}", filename, ext, url);
        FormatInfo format = new FormatInfo("direct", ext, null, null, null, null, null, null,
                "原始文件（." + ext + "）", false, audio, true);
        return new VideoInfo(filename, filename, thumbnail, null, null, null, "直链文件", null, null,
                List.of(format), null, null, false);
    }

    /** 从 URL 提取文件名（URI.getPath 自动解码），提取不到时按扩展名兜底 */
    public String filenameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                int i = path.lastIndexOf('/');
                String name = i >= 0 ? path.substring(i + 1) : path;
                if (!name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        String ext = extOf(url);
        return "direct-file." + (ext != null ? ext : "bin");
    }

    private String extOf(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return null;
            }
            int i = path.lastIndexOf('.');
            return i >= 0 ? path.substring(i + 1).toLowerCase(Locale.ROOT) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
