package io.github.zll.anydoc;

import io.github.zll.anydoc.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 活服务集成测试：针对真实运行的 anydoc-service（默认 http://127.0.0.1:8080）。
 *
 * <p>服务不可达时自动跳过（不阻塞 CI）。可用系统属性覆盖地址与 Token：
 * {@code -Danydoc.it.base-url=... -Danydoc.it.token=...}
 */
class LiveServiceTest {

    private static final String BASE_URL = System.getProperty("anydoc.it.base-url", "http://127.0.0.1:8080");
    private static final String TOKEN = System.getProperty("anydoc.it.token", "dev-token");

    private static AnydocClient client;

    @BeforeAll
    static void connect() {
        client = AnydocClient.builder()
                .baseUrl(BASE_URL)
                .token(TOKEN)
                .build();
        try {
            assumeTrue(client.health().isOk(), "anydoc-service 不可达，跳过活服务集成测试");
        } catch (RuntimeException e) {
            assumeTrue(false, "anydoc-service 不可达，跳过活服务集成测试: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("CSV 转 Markdown：表格结构保留")
    void convertCsv() {
        byte[] csv = "产品,数量\n服务器,12\n交换机,5\n".getBytes(StandardCharsets.UTF_8);
        ConversionResult result = client.convert(csv, "budget.csv");
        assertEquals("csv", result.format());
        assertTrue(result.markdown().contains("服务器"), "Markdown 应包含表格内容");
        assertTrue(result.markdown().contains("|"), "CSV 应输出 GFM 表格");
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("内容嗅探优先：PNG 伪装成 .docx 时按扩展名兜底解析失败 -> 422 malformed")
    void sniffingFallbackMalformed() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertThrows(io.github.zll.anydoc.exception.CorruptedDocumentException.class,
                () -> client.convert(png, "fake.docx"));
    }

    @Test
    @DisplayName("错误 Token -> 401 UnauthorizedException")
    void wrongToken() {
        AnydocClient bad = AnydocClient.builder()
                .baseUrl(BASE_URL)
                .token("definitely-wrong")
                .build();
        assertThrows(UnauthorizedException.class,
                () -> bad.convert("a,b\n".getBytes(StandardCharsets.UTF_8), "x.csv"));
    }
}
