package io.github.zll.anydoc.spring;

import io.github.zll.anydoc.AnydocClient;
import io.github.zll.anydoc.ServiceInfo;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Actuator 健康指示器：探测 anydoc-service 的 /healthz。
 *
 * <p>转换服务抖动不应导致整个应用被摘除，因此建议以 {@code management.health.*}
 * 配置将其排除出就绪探针聚合，仅用于面板展示与告警。
 */
public class AnydocHealthIndicator implements HealthIndicator {

    private final AnydocClient client;

    public AnydocHealthIndicator(AnydocClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            ServiceInfo info = client.health();
            Health.Builder builder = info.isOk() ? Health.up() : Health.down();
            info.details().forEach(builder::withDetail);
            return builder.build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", String.valueOf(e.getMessage()))
                    .build();
        }
    }
}
