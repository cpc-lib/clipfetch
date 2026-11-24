package cc.ivera.parser.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 票据与 HLS 子 token 的唯一内存真相：负责滑动续期、单用户配额、子资源上限与并发放流配额。
 */
@Slf4j
@Service
public class PlaybackTicketStore {

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong childSeq = new AtomicLong();
    private final AtomicLong streamSeq = new AtomicLong();
    private final Cache<String, PlaybackTicket> tickets;
    private final long maxLifetimeSeconds;
    private final int maxPerUser;
    private final int childMax;
    private final int maxStreamsPerTicket;

    public PlaybackTicketStore(
            @Value("${app.play.ticket-idle-seconds:1800}") long idleSeconds,
            @Value("${app.play.ticket-max-lifetime-seconds:21600}") long maxLifetimeSeconds,
            @Value("${app.play.max-tickets-per-user:3}") int maxPerUser,
            @Value("${app.play.child-max-per-ticket:4096}") int childMax,
            @Value("${app.play.max-streams-per-ticket:3}") int maxStreamsPerTicket) {
        this.maxLifetimeSeconds = maxLifetimeSeconds;
        this.maxPerUser = maxPerUser;
        this.childMax = childMax;
        this.maxStreamsPerTicket = maxStreamsPerTicket;
        this.tickets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(idleSeconds))
                .maximumSize(5000)
                // 不再在这里删除 merge 文件：同一 content-key 下多票据共享同一文件，
                // 文件生命周期统一交给 PlaybackMergeService.cleanupExpired() 按 TTL 清理。
                .build();
    }

    public synchronized PlaybackTicket create(Long userId, String pageUrl, String formatId, String mode,
                                              URI upstreamUri, String referer, String cookie,
                                              String userAgent, boolean useProxy, String mime,
                                              boolean audioOnly) {
        evictUserOldest(userId);
        LocalDateTime now = LocalDateTime.now();
        PlaybackTicket ticket = new PlaybackTicket(randomToken(32), userId, pageUrl, formatId, mode,
                upstreamUri, referer, cookie, userAgent, useProxy, mime, audioOnly,
                now, now.plusSeconds(maxLifetimeSeconds));
        tickets.put(ticket.getToken(), ticket);
        return ticket;
    }

    /** 票据不存在、空闲过期或超过硬上限返回 null；访问会刷新空闲时间 */
    public PlaybackTicket find(String token) {
        if (token == null) {
            return null;
        }
        PlaybackTicket ticket = tickets.getIfPresent(token);
        if (ticket == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(ticket.getHardExpiresAt())) {
            tickets.invalidate(token);
            return null;
        }
        ticket.touch(now);
        return ticket;
    }

    /**
     * 登记活跃字节流；超过单票据配额时关闭最早建立的流（新请求优先）。
     */
    public void registerStream(PlaybackTicket ticket, Closeable handle) {
        synchronized (ticket) {
            Deque<PlaybackTicket.ActiveStream> streams = ticket.getActiveStreams();
            while (streams.size() >= maxStreamsPerTicket) {
                PlaybackTicket.ActiveStream oldest = streams.pollFirst();
                if (oldest == null) {
                    break;
                }
                closeQuietly(oldest.handle());
                log.info("单票据并发放流超限，关闭最早流: userId={}, formatId={}",
                        ticket.getUserId(), ticket.getFormatId());
            }
            streams.addLast(new PlaybackTicket.ActiveStream(streamSeq.incrementAndGet(), handle));
        }
    }

    public void unregisterStream(PlaybackTicket ticket, Closeable handle) {
        ticket.getActiveStreams().removeIf(s -> s.handle() == handle);
    }

    /** 主动移除票据（如 merge 提交失败回滚），文件清理走 TTL 扫描。 */
    public void remove(PlaybackTicket ticket) {
        tickets.invalidate(ticket.getToken());
    }

    private void closeQuietly(Closeable handle) {
        try {
            handle.close();
        } catch (IOException e) {
            log.debug("关闭超限播放流失败: {}", e.getMessage());
        }
    }

    /**
     * 登记 HLS 子资源并返回子 token；同一票据内重复 URI 复用同一 token。
     */
    public String registerChild(PlaybackTicket ticket, URI uri, String contentTypeHint, boolean pinned) {
        synchronized (ticket) {
            for (Map.Entry<String, PlaybackTicket.Child> e : ticket.getChildren().entrySet()) {
                if (e.getValue().uri().equals(uri)) {
                    return e.getKey();
                }
            }
            if (ticket.getChildren().size() >= childMax) {
                evictChild(ticket);
            }
            String token = randomToken(18);
            ticket.getChildren().put(token,
                    new PlaybackTicket.Child(uri, contentTypeHint, pinned, childSeq.incrementAndGet()));
            return token;
        }
    }

    public PlaybackTicket.Child findChild(PlaybackTicket ticket, String childToken) {
        return ticket.getChildren().get(childToken);
    }

    private void evictUserOldest(Long userId) {
        long count = tickets.asMap().values().stream()
                .filter(t -> t.getUserId().equals(userId)).count();
        if (count < maxPerUser) {
            return;
        }
        tickets.asMap().values().stream()
                .filter(t -> t.getUserId().equals(userId))
                .min(Comparator.comparing(PlaybackTicket::getCreatedAt))
                .ifPresent(oldest -> {
                    tickets.invalidate(oldest.getToken());
                    log.info("播放票据超限淘汰: userId={}, formatId={}", userId, oldest.getFormatId());
                });
    }

    /** 优先淘汰最旧的非 pinned 分片；全部 pinned 时淘汰最旧项 */
    private void evictChild(PlaybackTicket ticket) {
        PlaybackTicket.Child victim = null;
        for (PlaybackTicket.Child c : ticket.getChildren().values()) {
            if (c.pinned()) {
                continue;
            }
            if (victim == null || c.seq() < victim.seq()) {
                victim = c;
            }
        }
        if (victim == null) {
            for (PlaybackTicket.Child c : ticket.getChildren().values()) {
                if (victim == null || c.seq() < victim.seq()) {
                    victim = c;
                }
            }
        }
        if (victim != null) {
            ticket.getChildren().values().remove(victim);
        }
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
