package io.github.zll.anydoc;

import io.github.zll.anydoc.exception.AnydocException;

/**
 * 批量转换的单文档结果：成功携带 {@link ConversionResult}，失败携带类型化异常。
 *
 * <p>批量接口的核心语义是<b>逐文档故障隔离</b>——单个文档的业务拒绝或瞬时故障
 * 不影响其他文档，由调用方按状态分流（重摄取/死信）。
 */
public record ConversionOutcome(InputDocument input, ConversionResult result, AnydocException error) {

    public static ConversionOutcome success(InputDocument input, ConversionResult result) {
        return new ConversionOutcome(input, result, null);
    }

    public static ConversionOutcome failure(InputDocument input, AnydocException error) {
        return new ConversionOutcome(input, null, error);
    }

    public boolean isSuccess() {
        return error == null && result != null;
    }
}
