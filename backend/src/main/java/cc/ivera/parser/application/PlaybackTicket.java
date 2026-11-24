package cc.ivera.parser.application;

import lombok.Getter;

import java.io.Closeable;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在线播放票据：浏览器原生媒体请求无法携带 JWT，创建票据后凭随机 token 访问代理端点。
 * 票据只存在内存，服务器重启后失效属于预期行为。
 */
@Getter
public class PlaybackTicket {

    private final String token;
    private final Long userId;
    private final String pageUrl;
    private final String formatId;
    /** progressive / hls / hls-decrypt / merge */
    private final String mode;
    private final URI upstreamUri;
    private final String referer;
    private final String cookie;
    private final String userAgent;
    private final boolean useProxy;
    private final String mime;
    private final boolean audioOnly;
    private final LocalDateTime createdAt;
    /** 最大寿命硬上限，不随访问续期 */
    private final LocalDateTime hardExpiresAt;
    /** 最近访问时间，由 Caffeine expireAfterAccess 驱动，仅用于观测 */
    private volatile LocalDateTime lastAccessAt;
    /** HLS 子资源 token → 真实上游地址 */
    private final Map<String, Child> children = new ConcurrentHashMap<>();
    /** 当前活跃的字节流句柄，超出配额时关闭最早建立的流 */
    private final Deque<ActiveStream> activeStreams = new ConcurrentLinkedDeque<>();
    /** merge 状态：null（非 merge）/ PREPARING / READY / FAILED */
    private volatile String mergeStatus;
    private final AtomicInteger mergeProgress = new AtomicInteger(0);
    private volatile String mergeError;
    /** 合并落盘文件，仅 merge 且 READY 时非空 */
    private volatile Path localFile;

    public PlaybackTicket(String token, Long userId, String pageUrl, String formatId, String mode,
                          URI upstreamUri, String referer, String cookie, String userAgent,
                          boolean useProxy, String mime, boolean audioOnly,
                          LocalDateTime createdAt, LocalDateTime hardExpiresAt) {
        this.token = token;
        this.userId = userId;
        this.pageUrl = pageUrl;
        this.formatId = formatId;
        this.mode = mode;
        this.upstreamUri = upstreamUri;
        this.referer = referer;
        this.cookie = cookie;
        this.userAgent = userAgent;
        this.useProxy = useProxy;
        this.mime = mime;
        this.audioOnly = audioOnly;
        this.createdAt = createdAt;
        this.hardExpiresAt = hardExpiresAt;
        this.lastAccessAt = createdAt;
    }

    public void touch(LocalDateTime now) {
        this.lastAccessAt = now;
    }

    public void startMerge() {
        this.mergeError = null;
        this.mergeProgress.set(0);
        this.mergeStatus = "PREPARING";
    }

    public void setMergeProgressValue(int percent) {
        this.mergeProgress.set(Math.max(0, Math.min(100, percent)));
    }

    public void mergeReady(Path file) {
        this.localFile = file;
        this.mergeProgress.set(100);
        this.mergeStatus = "READY";
    }

    public void mergeFailed(String error) {
        this.mergeError = error;
        this.mergeStatus = "FAILED";
    }

    /**
     * HLS 子资源：pinned 表示子 playlist / 密钥 / init map，不参与分片 FIFO 淘汰。
     */
    public record Child(URI uri, String contentTypeHint, boolean pinned, long seq) {
    }

    /** 活跃字节流：seq 用于确定最早建立的流 */
    public record ActiveStream(long seq, Closeable handle) {
    }
}
