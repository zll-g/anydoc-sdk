package io.github.zll.anydoc.exception;

/**
 * 熔断器开启：服务端近期失败率过高，客户端在冷却期内拒绝发起调用。
 *
 * <p>处理建议：不要立即重试；将任务转入兜底队列，冷却期（配置 openDuration）后自然恢复。
 */
public class AnydocCircuitOpenException extends AnydocException {

    public AnydocCircuitOpenException(String message) {
        super(message, null, "circuit_open", null);
    }
}
