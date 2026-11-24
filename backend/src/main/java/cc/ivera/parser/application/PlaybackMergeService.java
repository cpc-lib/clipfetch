package cc.ivera.parser.application;

import cc.ivera.parser.infrastructure.service.YtDlpService;
import cc.ivera.parser.infrastructure.sidecar.CctvNodeDecryptSidecar;
import cc.ivera.shared.web.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在线播放准备：merge 模式的后台任务（YouTube 音视频合并 / CCTV h5e 解密），
 * 落盘后由票据记录状态，前端轮询准备进度。
 * 全局并发受 PLAY_MERGE_CONCURRENCY 限制，单用户同时只允许 1 个准备任务。
 */
@Slf4j
@Service
public class PlaybackMergeService {

    private static final int MERGE_TIMEOUT_SECONDS = 1800;
    private static final int PROGRESS_TICK_SECONDS = 2;

    private final YtDlpService ytDlp;
    private final CctvNodeDecryptSidecar cctvDecrypt;
    private final Path cacheDir;
    private final long maxBytes;
    private final long ttlSeconds;
    private final Semaphore slots;
    private final ExecutorService executor;
    private final ScheduledExecutorService ticker;
    private final Map<Long, String> activeByUser = new ConcurrentHashMap<>();
    /** 内容级去重：contentKey → 正在处理该内容的 ticket token */
    private final Map<String, String> activeByContent = new ConcurrentHashMap<>();

    public PlaybackMergeService(YtDlpService ytDlp,
                                CctvNodeDecryptSidecar cctvDecrypt,
                                @Value("${app.play.merge-cache-dir:./downloads/play-cache}") String cacheDir,
                                @Value("${app.play.merge-max-bytes:4294967296}") long maxBytes,
                                @Value("${app.play.merge-ttl-seconds:86400}") long ttlSeconds,
                                @Value("${app.play.merge-concurrency:1}") int concurrency) {
        this.ytDlp = ytDlp;
        this.cctvDecrypt = cctvDecrypt;
        this.cacheDir = Path.of(cacheDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.ttlSeconds = ttlSeconds;
        int limit = Math.max(1, concurrency);
        this.slots = new Semaphore(limit);
        this.executor = Executors.newFixedThreadPool(limit, daemonFactory("play-merge"));
        this.ticker = Executors.newSingleThreadScheduledExecutor(daemonFactory("play-merge-progress"));
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.warn("创建播放缓存目录失败: {}", e.getMessage());
        }
        cleanupExpired();
    }

    /**
     * 提交合并任务；若磁盘已有 READY 文件则直接复用，否则走正常 acquire + 后台合并。
     * 单用户已有任务、同内容正在处理或全局并发已满时抛出中文业务错误。
     */
    public void submit(PlaybackTicket ticket, String url, String formatId, String cookieContent) {
        cleanupExpired();
        String key = contentKey(ticket);
        if (reuseOrAcquire(ticket, key)) {
            return;
        }
        ticket.startMerge();
        executor.submit(() -> run(ticket, url, formatId, cookieContent, key));
    }

    /**
     * 提交 CCTV h5e 解密任务：复用已有 READY 文件，否则后台 Node 解密落盘。
     */
    public void submitCctvDecrypt(PlaybackTicket ticket, String variantUrl) {
        cleanupExpired();
        String key = contentKey(ticket);
        if (reuseOrAcquire(ticket, key)) {
            return;
        }
        ticket.startMerge();
        executor.submit(() -> runCctv(ticket, variantUrl, key));
    }

    /** 原子地区分完成文件与处理中半成品，避免 --no-part 输出被重复建票误判为 READY。 */
    private boolean reuseOrAcquire(PlaybackTicket ticket, String contentKey) {
        synchronized (activeByContent) {
            if (activeByContent.containsKey(contentKey)) {
                throw new BusinessException("该视频正在准备中，请稍候再试");
            }
            Path readyFile = contentFilePath(contentKey);
            if (Files.isRegularFile(readyFile)) {
                ticket.mergeReady(readyFile);
                log.info("播放缓存命中: userId={}, file={}", ticket.getUserId(), readyFile.getFileName());
                return true;
            }
            acquire(ticket, contentKey);
            return false;
        }
    }

    /**
     * 单用户互斥 + 内容级互斥 + 全局并发占位；失败时抛出中文业务错误。
     */
    private void acquire(PlaybackTicket ticket, String contentKey) {
        Long userId = ticket.getUserId();
        if (activeByContent.putIfAbsent(contentKey, ticket.getToken()) != null) {
            throw new BusinessException("该视频正在准备中，请稍候再试");
        }
        if (activeByUser.putIfAbsent(userId, ticket.getToken()) != null) {
            activeByContent.remove(contentKey, ticket.getToken());
            throw new BusinessException("该视频正在准备中，请稍候再试");
        }
        if (!slots.tryAcquire()) {
            activeByUser.remove(userId, ticket.getToken());
            activeByContent.remove(contentKey, ticket.getToken());
            throw new BusinessException("正在准备其他视频，请稍候再试");
        }
    }

    /** 内容级去重键：用户 + 页面 URL + 清晰度 → SHA-256 前 16 字符 */
    private String contentKey(PlaybackTicket ticket) {
        String raw = ticket.getUserId() + "|" + ticket.getPageUrl() + "|" + ticket.getFormatId();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    private Path contentFilePath(String contentKey) {
        return cacheDir.resolve(contentKey + ".mp4");
    }

    private void run(PlaybackTicket ticket, String url, String formatId, String cookieContent, String contentKey) {
        Path output = contentFilePath(contentKey);
        AtomicBoolean parsed = new AtomicBoolean(false);
        ScheduledFuture<?> tick = ticker.scheduleAtFixedRate(() -> {
            if (!parsed.get()) {
                int current = ticket.getMergeProgress().get();
                if (current < 95) {
                    ticket.setMergeProgressValue(current + 5);
                }
            }
        }, PROGRESS_TICK_SECONDS, PROGRESS_TICK_SECONDS, TimeUnit.SECONDS);
        try {
            ytDlp.mergeToFile(url, formatId, cookieContent, output, maxBytes,
                    MERGE_TIMEOUT_SECONDS, percent -> {
                        parsed.set(true);
                        ticket.setMergeProgressValue(percent);
                    });
            if (!Files.isRegularFile(output)) {
                throw new BusinessException("合并未生成文件，请稍后重试");
            }
            ticket.mergeReady(output);
            log.info("播放合并完成: userId={}, file={}", ticket.getUserId(), output.getFileName());
        } catch (BusinessException e) {
            ticket.mergeFailed(e.getMessage());
            log.warn("播放合并失败: userId={}, reason={}", ticket.getUserId(), e.getMessage());
            deleteQuietly(output);
        } catch (Exception e) {
            ticket.mergeFailed("合并失败，请稍后重试");
            log.warn("播放合并异常: userId={}, reason={}", ticket.getUserId(), e.getMessage());
            deleteQuietly(output);
        } finally {
            tick.cancel(false);
            activeByUser.remove(ticket.getUserId(), ticket.getToken());
            activeByContent.remove(contentKey, ticket.getToken());
            slots.release();
        }
    }

    private void runCctv(PlaybackTicket ticket, String variantUrl, String contentKey) {
        Path output = contentFilePath(contentKey);
        Path workDir = cacheDir.resolve("cctv-" + contentKey);
        try {
            Path decrypted = cctvDecrypt.decryptToDirectory(variantUrl, workDir, (current, total) -> {
                if (total > 0) {
                    ticket.setMergeProgressValue(Math.min(99, current * 100 / total));
                }
            });
            Files.move(decrypted, output, StandardCopyOption.REPLACE_EXISTING);
            ticket.mergeReady(output);
            log.info("播放解密完成: userId={}, file={}", ticket.getUserId(), output.getFileName());
        } catch (BusinessException e) {
            ticket.mergeFailed(e.getMessage());
            log.warn("播放解密失败: userId={}, reason={}", ticket.getUserId(), e.getMessage());
            deleteQuietly(output);
        } catch (Exception e) {
            ticket.mergeFailed("解密失败，请稍后重试");
            log.warn("播放解密异常: userId={}, reason={}", ticket.getUserId(), e.getMessage());
            deleteQuietly(output);
        } finally {
            deleteRecursively(workDir);
            activeByUser.remove(ticket.getUserId(), ticket.getToken());
            activeByContent.remove(contentKey, ticket.getToken());
            slots.release();
        }
    }

    /** 清扫超过 TTL 的合并缓存文件（启动、提交合并与创建票据时调用） */
    public void cleanupExpired() {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        try (var entries = Files.list(cacheDir)) {
            entries.forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    boolean expired = Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                    if (Files.isDirectory(p)) {
                        // CCTV 解密的临时工作目录（cctv-<ticket>），异常退出时可能残留
                        if (name.startsWith("cctv-") && expired) {
                            deleteRecursively(p);
                            log.info("清理过期解密目录: {}", name);
                        }
                        return;
                    }
                    if (name.endsWith(".mp4") && expired) {
                        Files.deleteIfExists(p);
                        log.info("清理过期播放缓存: {}", name);
                    }
                } catch (IOException e) {
                    log.warn("清理播放缓存失败: {}", e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("扫描播放缓存目录失败: {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var entries = Files.list(dir)) {
            entries.forEach(p -> {
                try {
                    if (Files.isDirectory(p)) {
                        deleteRecursively(p);
                    } else {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("清理解密目录失败: {}", dir.getFileName());
            return;
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
