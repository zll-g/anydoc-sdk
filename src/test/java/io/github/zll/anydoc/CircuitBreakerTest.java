package io.github.zll.anydoc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 熔断器状态机单测（无网络依赖）。 */
class CircuitBreakerTest {

    private CircuitBreaker breaker(int window, double threshold, Duration open) {
        return new CircuitBreaker(CircuitBreaker.CircuitBreakerConfig.of(window, threshold, open));
    }

    @Test
    @DisplayName("窗口填满且失败率达标 → OPEN，冷却期内拒绝")
    void opensOnFailureRate() {
        CircuitBreaker cb = breaker(4, 0.5, Duration.ofSeconds(60));
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();   // 3/4 = 75% ≥ 50% → OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.tryAcquire(), "冷却期内应拒绝调用");
    }

    @Test
    @DisplayName("失败率未达标 → 保持 CLOSED")
    void staysClosedBelowThreshold() {
        CircuitBreaker cb = breaker(4, 0.5, Duration.ofSeconds(60));
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordSuccess();   // 1/4 = 25% < 50%
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.tryAcquire());
    }

    @Test
    @DisplayName("冷却期结束 → HALF_OPEN 放行探测；探测成功 → CLOSED")
    void halfOpenProbeSuccess() throws Exception {
        CircuitBreaker cb = breaker(2, 0.5, Duration.ofMillis(50));
        cb.recordFailure();
        cb.recordFailure();   // OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        Thread.sleep(80);     // 等冷却期结束
        assertTrue(cb.tryAcquire(), "冷却后应放行探测");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordSuccess();   // 探测成功
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.tryAcquire());
    }

    @Test
    @DisplayName("HALF_OPEN 探测失败 → 重新 OPEN")
    void halfOpenProbeFailure() throws Exception {
        CircuitBreaker cb = breaker(2, 0.5, Duration.ofMillis(50));
        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(80);
        assertTrue(cb.tryAcquire());          // 进入 HALF_OPEN
        cb.recordFailure();                   // 探测失败
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.tryAcquire());
    }

    @Test
    @DisplayName("窗口滑动：旧成功逐出后失败率才达标")
    void slidingWindow() {
        CircuitBreaker cb = breaker(3, 0.6, Duration.ofSeconds(60));
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordFailure();   // 1/3 不达标
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        cb.recordFailure();   // 窗口 [S,F,F] → 2/3 ≥ 60% → OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    @DisplayName("禁用配置 → 恒放行且不改变状态")
    void disabled() {
        CircuitBreaker cb = new CircuitBreaker(CircuitBreaker.CircuitBreakerConfig.disabled());
        for (int i = 0; i < 100; i++) {
            cb.recordFailure();
        }
        assertTrue(cb.tryAcquire());
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }
}
