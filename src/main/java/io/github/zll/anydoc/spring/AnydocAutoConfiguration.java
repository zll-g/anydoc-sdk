package io.github.zll.anydoc.spring;

import io.github.zll.anydoc.AnydocClient;
import io.github.zll.anydoc.CircuitBreaker;
import io.github.zll.anydoc.RetryPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 自动装配：引入 sdk-client 依赖即生效。
 *
 * <p>提供 {@link AnydocClient} 单例 Bean；业务侧直接注入使用：
 * <pre>{@code
 * @Service
 * public class DocumentIngestionService {
 *     private final AnydocClient anydoc;
 *     ...
 * }
 * }</pre>
 */
@AutoConfiguration
@EnableConfigurationProperties(AnydocProperties.class)
public class AnydocAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AnydocClient anydocClient(AnydocProperties properties) {
        AnydocProperties.Circuit c = properties.getCircuit();
        CircuitBreaker.CircuitBreakerConfig circuitConfig = c.isEnabled()
                ? CircuitBreaker.CircuitBreakerConfig.of(
                        c.getWindowSize(), c.getFailureRateThreshold(), c.getOpenDuration())
                : CircuitBreaker.CircuitBreakerConfig.disabled();
        return AnydocClient.builder()
                .baseUrl(properties.getBaseUrl())
                .token(properties.getToken())
                .connectTimeout(properties.getConnectTimeout())
                .requestTimeout(properties.getRequestTimeout())
                .retry(RetryPolicy.of(
                        properties.getRetry().getMaxAttempts(),
                        properties.getRetry().getInitialBackoff(),
                        properties.getRetry().getMultiplier()))
                .circuitBreaker(circuitConfig)
                .build();
    }

    /** Actuator 健康指示器：仅在 actuator 存在且未关闭时注册。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.boot.actuate.health.HealthIndicator.class)
    @ConditionalOnProperty(prefix = "rag.anydoc.health", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class HealthIndicatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AnydocHealthIndicator anydocHealthIndicator(AnydocClient client) {
            return new AnydocHealthIndicator(client);
        }
    }
}
