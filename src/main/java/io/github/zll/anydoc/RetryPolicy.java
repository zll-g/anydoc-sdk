package io.github.zll.anydoc;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试策略（仅对瞬时故障生效：网络异常、超时、5xx；业务拒绝 4xx 不重试）。
 *
 * <p>退避公式：{@code initialBackoff × multiplier^(attempt-1)}，封顶 {@code maxBackoff}，
 * 叠加 ±20% 随机抖动，避免重试风暴。
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;

    private RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier 必须 >= 1.0");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
    }

    /** 默认策略：共 3 次尝试，300ms 起步，×2 递增，封顶 2s。 */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(300), 2.0, Duration.ofSeconds(2));
    }

    /** 不重试（仅尝试 1 次）。 */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);
    }

    /** 自定义策略（封顶 2s）。 */
    public static RetryPolicy of(int maxAttempts, Duration initialBackoff, double multiplier) {
        return new RetryPolicy(maxAttempts, initialBackoff, multiplier, Duration.ofSeconds(2));
    }

    /** 完全自定义。 */
    public static RetryPolicy of(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, multiplier, maxBackoff);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 计算第 attempt 次失败后的退避时长（含 ±20% 抖动）。 */
    public Duration backoff(int attempt) {
        if (maxAttempts <= 1 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        double base = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        long capped = Math.min((long) base, maxBackoff.toMillis());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble(0.4);
        return Duration.ofMillis(Math.max(0, (long) (capped * jitter)));
    }
}
