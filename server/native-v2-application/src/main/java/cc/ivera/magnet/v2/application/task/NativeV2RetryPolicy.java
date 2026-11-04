package cc.ivera.magnet.v2.application.task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** 指数退避 + 抖动；达到上限后返回空，交由用户手动重试。 */
public final class NativeV2RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double jitterRatio;
    private final RandomGenerator random;

    public NativeV2RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay,
                               double jitterRatio, RandomGenerator random) {
        if (maxAttempts <= 0 || initialDelay.isNegative() || initialDelay.isZero()
                || maxDelay.compareTo(initialDelay) < 0 || jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("重试策略参数非法");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.jitterRatio = jitterRatio;
        this.random = random;
    }

    public Optional<LocalDateTime> nextRetry(int retryCount, LocalDateTime now) {
        if (retryCount >= maxAttempts) {
            return Optional.empty();
        }
        long baseMillis = initialDelay.toMillis() << Math.min(retryCount, 20);
        long cappedMillis = Math.min(baseMillis, maxDelay.toMillis());
        long jitterMillis = (long) (cappedMillis * jitterRatio * random.nextDouble());
        return Optional.of(now.plus(Duration.ofMillis(cappedMillis + jitterMillis)));
    }
}
