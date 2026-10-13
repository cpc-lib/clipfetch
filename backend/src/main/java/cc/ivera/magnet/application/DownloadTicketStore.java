package cc.ivera.magnet.application;

import cc.ivera.shared.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived capability for browser-native GET / Range requests. Never expose
 * JWT access tokens in URLs. Ticket is bound to user, torrent and file index.
 * In-memory storage intentionally requires a single Spring Boot instance
 * (or sticky routing) for an active browser download.
 */
@Component
public class DownloadTicketStore {
    public record Ticket(long userId, String hash, int fileIndex, Instant expiresAt) {}

    private static final int MAX_TICKETS = 10000;
    private static final long TTL_SECONDS = 4 * 60 * 60;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    public String create(long userId, String hash, int fileIndex) {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        if (tickets.size() >= MAX_TICKETS) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "同时下载请求过多，请稍后重试");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(token, new Ticket(userId, hash, fileIndex, now.plusSeconds(TTL_SECONDS)));
        return token;
    }

    public Ticket require(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "下载凭证无效");
        }
        Ticket ticket = tickets.get(token);
        if (ticket == null || !ticket.expiresAt().isAfter(Instant.now())) {
            tickets.remove(token);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "下载凭证已失效，请从页面重新开始");
        }
        return ticket;
    }

    public void revokeTorrent(String hash) {
        tickets.entrySet().removeIf(e -> e.getValue().hash().equalsIgnoreCase(hash));
    }
}
