package io.github.zll.anydoc;

import java.util.Map;

/**
 * 服务健康信息（GET /healthz 响应）。
 *
 * @param status  服务状态（正常为 "ok"）
 * @param details 响应中的全部键值对（含服务端版本等元数据）
 */
public record ServiceInfo(String status, Map<String, Object> details) {

    public boolean isOk() {
        return "ok".equalsIgnoreCase(status);
    }
}
