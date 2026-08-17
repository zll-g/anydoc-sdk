package io.github.zll.anydoc;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内建熔断器（计数滑动窗口）：防止服务端故障时调用方持续施压。
 *
 * <p>状态机：CLOSED →（窗口内失败率 ≥ 阈值）→ OPEN →（冷却期后）→ HALF_OPEN
 * →（探测成功）→ CLOSED /（探测失败）→ OPEN。
 *
 * <p>语义约定：仅<b>瞬时故障</b>（网络/超时/5xx）计入失败；业务拒绝（4xx）不计入，
 * 与服务端错误契约保持一致。线程安全。
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final CircuitBreakerConfig config;
    private final boolean[] outcomes;      // true = failure
    private int index;
    private int count;
    private int failures;
    private long openUntilNanos;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
        this.outcomes = new boolean[Math.max(1, config.windowSize())];
    }

    /** @return false 表示熔断开启中，调用方应立即放弃本次调用 */
    public synchronized boolean tryAcquire() {
        if (!config.enabled()) {
            return true;
        }
        if (state.get() == State.OPEN) {
            if (System.nanoTime() >= openUntilNanos) {
                state.set(State.HALF_OPEN);   // 放行一次探测
                return true;
            }
            return false;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        if (!config.enabled()) {
            return;
        }
        if (state.get() == State.HALF_OPEN) {
            reset();
            return;
        }
        record(false);
    }

    public synchronized void recordFailure() {
        if (!config.enabled()) {
            return;
        }
        if (state.get() == State.HALF_OPEN) {
            open();
            return;
        }
        record(true);
        if (count >= config.windowSize()
                && (double) failures / count >= config.failureRateThreshold()) {
            open();
        }
    }

    public State state() {
        return state.get();
    }

    private void record(boolean failure) {
        if (count >= outcomes.length && outcomes[index]) {
            failures--;      // 被逐出的旧结果是失败
        }
        outcomes[index] = failure;
        if (failure) {
            failures++;
        }
        index = (index + 1) % outcomes.length;
        count = Math.min(count + 1, outcomes.length);
    }

    private void open() {
        state.set(State.OPEN);
        openUntilNanos = System.nanoTime() + config.openDuration().toNanos();
    }

    private void reset() {
        state.set(State.CLOSED);
        index = 0;
        count = 0;
        failures = 0;
    }

    /**
     * 熔断器配置。
     *
     * @param enabled              是否启用
     * @param windowSize           滑动窗口大小（请求数）
     * @param failureRateThreshold 失败率阈值（0~1），窗口填满后生效
     * @param openDuration         熔断开启时长（冷却期）
     */
    public record CircuitBreakerConfig(
            boolean enabled, int windowSize, double failureRateThreshold, Duration openDuration) {

        /** 默认：窗口 20、失败率 50%、冷却 10s。 */
        public static CircuitBreakerConfig defaults() {
            return new CircuitBreakerConfig(true, 20, 0.5, Duration.ofSeconds(10));
        }

        /** 禁用熔断。 */
        public static CircuitBreakerConfig disabled() {
            return new CircuitBreakerConfig(false, 1, 1.0, Duration.ZERO);
        }

        public static CircuitBreakerConfig of(int windowSize, double failureRateThreshold, Duration openDuration) {
            if (windowSize < 1) {
                throw new IllegalArgumentException("windowSize 必须 >= 1");
            }
            if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
                throw new IllegalArgumentException("failureRateThreshold 必须在 (0, 1]");
            }
            return new CircuitBreakerConfig(true, windowSize, failureRateThreshold, openDuration);
        }
    }
}
