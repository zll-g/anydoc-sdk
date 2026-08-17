package io.github.zll.anydoc;

import io.github.zll.anydoc.exception.AnydocException;

import java.time.Duration;

/**
 * 转换事件监听器（可观测性扩展点）。
 *
 * <p>典型用法：在回调中记录 Micrometer 指标 / 埋点 / 审计日志，
 * SDK 本身不强制依赖任何监控框架。实现方必须快速返回且不抛异常。
 */
public interface ConversionListener {

    /** 转换成功。 */
    default void onSuccess(String requestId, String filename, ConversionResult result, Duration elapsed) {
    }

    /** 转换失败（含重试耗尽后的最终失败与业务拒绝）。 */
    default void onFailure(String requestId, String filename, AnydocException error, Duration elapsed) {
    }
}
