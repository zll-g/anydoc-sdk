package io.github.zll.anydoc.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot 配置项，前缀 {@code rag.anydoc}。
 *
 * <pre>
 * rag:
 *   anydoc:
 *     base-url: http://anydoc-service:8080
 *     token: ${ANYDOC_SERVICE_TOKEN:dev-token}
 *     connect-timeout: 2s
 *     request-timeout: 10s
 *     retry:
 *       max-attempts: 3
 *       initial-backoff: 300ms
 *       multiplier: 2.0
 *     health:
 *       enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "rag.anydoc")
public class AnydocProperties {

    /** anydoc-service 地址（K8s 内用 Service DNS）。 */
    private String baseUrl = "http://localhost:8080";

    /** Bearer Token，对应服务端 ANYDOC_SERVICE_TOKEN（服务端默认 dev-token）。 */
    private String token = "dev-token";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration requestTimeout = Duration.ofSeconds(10);

    private final Retry retry = new Retry();

    private final Circuit circuit = new Circuit();

    private final Health health = new Health();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Retry getRetry() {
        return retry;
    }

    public Circuit getCircuit() {
        return circuit;
    }

    public Health getHealth() {
        return health;
    }

    public static class Retry {
        /** 总尝试次数（含首次）。 */
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(300);
        private double multiplier = 2.0;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }
    }

    public static class Circuit {
        /** 是否启用熔断器。 */
        private boolean enabled = true;
        /** 滑动窗口大小（请求数）。 */
        private int windowSize = 20;
        /** 失败率阈值（0~1），窗口填满后生效。 */
        private double failureRateThreshold = 0.5;
        /** 熔断开启时长（冷却期）。 */
        private Duration openDuration = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public double getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(double failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }

    public static class Health {
        /** 是否注册 Actuator 健康指示器（依赖 spring-boot-actuator 时才生效）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
