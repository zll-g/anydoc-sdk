package io.github.zll.anydoc;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@link AnydocClient} 构建器（fluent API）。
 *
 * <pre>{@code
 * AnydocClient client = AnydocClient.builder()
 *         .baseUrl("http://anydoc-service:8080")
 *         .token(token)
 *         .connectTimeout(Duration.ofSeconds(2))
 *         .requestTimeout(Duration.ofSeconds(10))
 *         .retry(RetryPolicy.of(3, Duration.ofMillis(300), 2.0))
 *         .build();
 * }</pre>
 */
public final class AnydocClientBuilder {

    private String baseUrl;
    private String token;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private RetryPolicy retryPolicy = RetryPolicy.defaults();
    private CircuitBreaker.CircuitBreakerConfig circuitBreakerConfig = CircuitBreaker.CircuitBreakerConfig.defaults();
    private final List<ConversionListener> listeners = new ArrayList<>();
    private HttpClient httpClient;

    AnydocClientBuilder() {
    }

    /** 服务地址，如 http://anydoc-service:8080（必填）。 */
    public AnydocClientBuilder baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    /** 服务端 Bearer Token，对应 ANYDOC_SERVICE_TOKEN（必填）。 */
    public AnydocClientBuilder token(String token) {
        this.token = token;
        return this;
    }

    /** 连接超时，默认 2s。 */
    public AnydocClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /** 单次请求超时，默认 10s（转换本身毫秒级，勿无限调大）。 */
    public AnydocClientBuilder requestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        return this;
    }

    /** 重试策略，默认 3 次尝试 + 指数退避。 */
    public AnydocClientBuilder retry(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    /** 熔断器配置，默认窗口 20 / 失败率 50% / 冷却 10s；{@code CircuitBreakerConfig.disabled()} 可关闭。 */
    public AnydocClientBuilder circuitBreaker(CircuitBreaker.CircuitBreakerConfig config) {
        this.circuitBreakerConfig = config;
        return this;
    }

    /** 注册可观测性监听器（可多次调用）。 */
    public AnydocClientBuilder listener(ConversionListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
        return this;
    }

    /** 注入自定义 JDK HttpClient（如需共享连接池/代理/SSL 上下文）。 */
    public AnydocClientBuilder httpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        return this;
    }

    public AnydocClient build() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        URI uri = URI.create(baseUrl.trim().replaceAll("/+$", ""));
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("baseUrl 必须是 http/https 地址: " + baseUrl);
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "token 不能为空：请配置 ANYDOC_SERVICE_TOKEN（服务端默认值为 dev-token）");
        }
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("超时时间必须为正数");
        }
        return new DefaultAnydocClient(uri, token.trim(), connectTimeout, requestTimeout,
                retryPolicy, Collections.unmodifiableList(new ArrayList<>(listeners)), httpClient,
                circuitBreakerConfig);
    }
}
