package cc.ivera.magnet.v2.infrastructure.quota;

import cc.ivera.magnet.v2.application.port.NativeV2Quota;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.infrastructure.config.NativeV2Properties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RedisNativeV2Quota implements NativeV2Quota {

    private static final long RATE_WINDOW_MS = 60_000;
    private static final String PREFIX = "magnet:v2:quota:";

    private static final DefaultRedisScript<Long> RATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if current == 1 or ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local expires = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZCARD', KEYS[1]) >= limit then
              local latest = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
              if #latest == 2 then redis.call('PEXPIREAT', KEYS[1], math.floor(tonumber(latest[2]))) end
              return 0
            end
            redis.call('ZADD', KEYS[1], expires, ARGV[4])
            redis.call('PEXPIREAT', KEYS[1], expires)
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            if redis.call('ZCARD', KEYS[1]) == 0 then
              redis.call('DEL', KEYS[1])
            else
              local latest = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
              if #latest == 2 then redis.call('PEXPIREAT', KEYS[1], math.floor(tonumber(latest[2]))) end
            end
            return removed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int parseRateLimit;
    private final int ticketRateLimit;
    private final int parseConcurrentLimit;
    private final long parseLeaseTtlMs;

    public RedisNativeV2Quota(StringRedisTemplate redis, NativeV2Properties properties) {
        this.redis = Objects.requireNonNull(redis);
        this.parseRateLimit = properties.getParseRatePerMinute();
        this.ticketRateLimit = properties.getTicketRatePerMinute();
        this.parseConcurrentLimit = properties.getParseConcurrentPerUser();
        // Parse concurrency covers the full metadata resolution window, including Worker retries.
        this.parseLeaseTtlMs = properties.getMetadataTimeoutMs();
    }

    @Override
    public void consumeParseRate(long userId) {
        consumeRate(key("parse-rate", userId), parseRateLimit);
    }

    @Override
    public Lease acquireParseLease(long userId) {
        String key = key("parse-lease", userId);
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expiresAt = now + parseLeaseTtlMs;
        Long acquired = execute(ACQUIRE_SCRIPT, key,
                Long.toString(now), Long.toString(expiresAt), Integer.toString(parseConcurrentLimit), token);
        if (acquired == null) {
            throw unavailable(null);
        }
        if (acquired == 0) {
            throw limited();
        }
        return new RedisLease(key, token);
    }

    @Override
    public void consumeTicketRate(long userId) {
        consumeRate(key("ticket-rate", userId), ticketRateLimit);
    }

    private void consumeRate(String key, int limit) {
        Long current = execute(RATE_SCRIPT, key, Long.toString(RATE_WINDOW_MS));
        if (current == null) {
            throw unavailable(null);
        }
        if (current > limit) {
            throw limited();
        }
    }

    private String key(String kind, long userId) {
        if (userId <= 0) {
            throw new V2DomainException(ErrorCodes.VALIDATION, "用户标识无效，请重新登录后重试");
        }
        return PREFIX + kind + ":" + userId;
    }

    private Long execute(DefaultRedisScript<Long> script, String key, String... args) {
        try {
            return redis.execute(script, List.of(key), (Object[]) args);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    private static V2DomainException limited() {
        return new V2DomainException(ErrorCodes.RATE_LIMITED, "请求过于频繁，请稍后重试");
    }

    private static V2DomainException unavailable(Throwable cause) {
        V2DomainException error = new V2DomainException(
                ErrorCodes.REDIS_UNAVAILABLE, "配额服务暂不可用，请稍后重试");
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    private final class RedisLease implements Lease {

        private final String key;
        private final String token;
        private boolean closed;

        private RedisLease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            Long ignored = execute(RELEASE_SCRIPT, key, token);
            if (ignored == null) {
                throw unavailable(null);
            }
            closed = true;
        }
    }
}
