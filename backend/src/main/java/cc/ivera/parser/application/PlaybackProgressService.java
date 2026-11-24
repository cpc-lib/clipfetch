package cc.ivera.parser.application;

import cc.ivera.parser.domain.PlaybackProgress;
import cc.ivera.parser.domain.PlaybackProgressMapper;
import cc.ivera.parser.domain.Platform;
import cc.ivera.shared.web.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Service
public class PlaybackProgressService {

    private static final int MAX_MEDIA_SECONDS = 86400;
    private static final Set<String> MODES = Set.of("progressive", "hls", "hls-decrypt", "merge");

    private final PlaybackProgressMapper mapper;
    private final int resumeMinSeconds;
    private final int resumeCompletePercent;

    public PlaybackProgressService(
            PlaybackProgressMapper mapper,
            @Value("${app.play.resume-min-seconds:5}") int resumeMinSeconds,
            @Value("${app.play.resume-complete-percent:95}") int resumeCompletePercent) {
        this.mapper = mapper;
        this.resumeMinSeconds = resumeMinSeconds;
        this.resumeCompletePercent = resumeCompletePercent;
    }

    public PlaybackProgress find(Long userId, String url) {
        String normalized = normalize(url);
        return mapper.findOwned(userId, sourceKey(normalized));
    }

    public PlaybackProgress save(Long userId, SaveCommand command) {
        String sourceUrl = validate(command);
        String normalized = normalize(sourceUrl);
        LocalDateTime now = LocalDateTime.now();
        PlaybackProgress progress = PlaybackProgress.builder()
                .userId(userId)
                .sourceKey(sourceKey(normalized))
                .sourceUrl(sourceUrl)
                .platform(Platform.from(sourceUrl).name())
                .title(trimToNull(command.title()))
                .formatId(trimToNull(command.formatId()))
                .mode(trimToNull(command.mode()))
                .positionSeconds(command.positionSeconds())
                .durationSeconds(command.durationSeconds())
                .completed(isCompleted(command.positionSeconds(), command.durationSeconds(),
                        command.completed()))
                .createdAt(now)
                .updatedAt(now)
                .deleted(0L)
                .build();

        PlaybackProgress existing = mapper.findOwned(userId, progress.getSourceKey());
        if (existing == null) {
            try {
                mapper.insertProgress(progress);
                return progress;
            } catch (DuplicateKeyException e) {
                existing = mapper.findOwned(userId, progress.getSourceKey());
                if (existing == null) {
                    throw e;
                }
            }
        }
        progress.setId(existing.getId());
        progress.setCreatedAt(existing.getCreatedAt());
        mapper.updateProgress(progress);
        return progress;
    }

    public int resumeSeconds(PlaybackProgress progress) {
        if (progress == null || isCompleted(progress)
                || progress.getPositionSeconds() == null
                || progress.getPositionSeconds() < resumeMinSeconds) {
            return 0;
        }
        return progress.getPositionSeconds();
    }

    public boolean isCompleted(PlaybackProgress progress) {
        return progress != null && isCompleted(progress.getPositionSeconds(),
                progress.getDurationSeconds(), progress.getCompleted());
    }

    private boolean isCompleted(Integer position, Integer duration, Boolean completed) {
        if (Boolean.TRUE.equals(completed)) {
            return true;
        }
        return position != null && duration != null && duration > 0
                && (long) position * 100 >= (long) duration * resumeCompletePercent;
    }

    private String validate(SaveCommand command) {
        if (command == null || command.url() == null || command.url().isBlank()) {
            throw new BusinessException("请输入视频链接");
        }
        String url = command.url().trim();
        if (url.length() > 2048) {
            throw new BusinessException("视频链接不能超过 2048 个字符");
        }
        Integer position = command.positionSeconds();
        if (position == null || position < 0 || position > MAX_MEDIA_SECONDS) {
            throw new BusinessException("播放位置必须在 0 到 86400 秒之间");
        }
        Integer duration = command.durationSeconds();
        if (duration != null && (duration < 1 || duration > MAX_MEDIA_SECONDS)) {
            throw new BusinessException("视频时长必须在 1 到 86400 秒之间");
        }
        if (duration != null && position > duration + 5) {
            throw new BusinessException("播放位置不能超过视频总时长");
        }
        if (command.title() != null && command.title().length() > 512) {
            throw new BusinessException("视频标题不能超过 512 个字符");
        }
        if (command.formatId() != null && command.formatId().length() > 256) {
            throw new BusinessException("清晰度标识不能超过 256 个字符");
        }
        String mode = trimToNull(command.mode());
        if (mode != null && !MODES.contains(mode)) {
            throw new BusinessException("播放模式仅支持 progressive、hls、hls-decrypt 或 merge");
        }
        return url;
    }

    private String normalize(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException("请输入视频链接");
        }
        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("请输入有效的 http(s) 视频链接");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if ((!scheme.equals("http") && !scheme.equals("https")) || host == null || host.isBlank()) {
            throw new BusinessException("请输入有效的 http(s) 视频链接");
        }
        StringBuilder normalized = new StringBuilder()
                .append(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port >= 0 && !(scheme.equals("http") && port == 80)
                && !(scheme.equals("https") && port == 443)) {
            normalized.append(':').append(port);
        }
        if (uri.getRawPath() != null) {
            normalized.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            normalized.append('?').append(uri.getRawQuery());
        }
        return normalized.toString();
    }

    private String sourceKey(String normalizedUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record SaveCommand(String url, Integer positionSeconds, Integer durationSeconds,
                              String formatId, String mode, String title, Boolean completed) {
    }
}
