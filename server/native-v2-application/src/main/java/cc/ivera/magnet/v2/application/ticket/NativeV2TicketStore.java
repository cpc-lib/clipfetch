package cc.ivera.magnet.v2.application.ticket;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内短时 ticket；TTL 内允许多个 Range 请求，不跨实例共享。 */
public final class NativeV2TicketStore {

    public record Ticket(String token, long userId, String taskUuid, String resourceUuid,
                         int fileIndex, String relativePath, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public NativeV2TicketStore(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ticket TTL 必须为正数");
        }
        this.ttl = ttl;
    }

    public String issue(long userId, String taskUuid, String resourceUuid,
                        int fileIndex, String relativePath, Instant now) {
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(token, new Ticket(token, userId, taskUuid, resourceUuid, fileIndex,
                relativePath, now.plus(ttl)));
        return token;
    }

    public Optional<Ticket> find(String token, Instant now) {
        if (token == null || token.length() < 32 || token.length() > 64) {
            return Optional.empty();
        }
        Ticket ticket = tickets.get(token);
        if (ticket == null) {
            return Optional.empty();
        }
        if (!ticket.expiresAt().isAfter(now)) {
            tickets.remove(token, ticket);
            return Optional.empty();
        }
        return Optional.of(ticket);
    }
}
