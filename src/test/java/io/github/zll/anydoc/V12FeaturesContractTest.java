package io.github.zll.anydoc;

import com.sun.net.httpserver.HttpServer;
import io.github.zll.anydoc.exception.AnydocUnavailableException;
import io.github.zll.anydoc.exception.UnsupportedDocumentException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.2 新特性契约测试：cacheHit / X-Content-SHA256 / 429 映射 / 批量转换。 */
class V12FeaturesContractTest {

    private static HttpServer server;
    private static volatile Handler handler;
    private static final AtomicReference<String> LAST_SHA256 = new AtomicReference<>();

    @FunctionalInterface
    private interface Handler {
        Response handle(String body);
    }

    private record Response(int status, String body) {
    }

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert", exchange -> {
            LAST_SHA256.set(exchange.getRequestHeaders().getFirst("X-Content-SHA256"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response resp = handler.handle(body);
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(resp.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        LAST_SHA256.set(null);
    }

    private static final byte[] DOC = "feature-doc".getBytes(StandardCharsets.UTF_8);

    private AnydocClient newClient() {
        return AnydocClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("t")
                .retry(RetryPolicy.none())
                .circuitBreaker(CircuitBreaker.CircuitBreakerConfig.disabled())
                .build();
    }

    @Test
    @DisplayName("cache_hit=true 被解析进 ConversionResult")
    void cacheHitParsed() {
        handler = body -> new Response(200, """
                {"markdown":"# x","format":"csv","elapsed_ms":0.5,"input_bytes":11,"cache_hit":true}
                """);
        ConversionResult result = newClient().convert(DOC, "a.csv");
        assertTrue(result.cacheHit());
    }

    @Test
    @DisplayName("缺省 cache_hit → false（向后兼容旧服务端）")
    void cacheHitDefaultsFalse() {
        handler = body -> new Response(200, """
                {"markdown":"# x","format":"csv","elapsed_ms":0.5,"input_bytes":11}
                """);
        ConversionResult result = newClient().convert(DOC, "a.csv");
        assertFalse(result.cacheHit());
    }

    @Test
    @DisplayName("请求携带 X-Content-SHA256（64 位十六进制）")
    void sha256HeaderSent() {
        handler = body -> new Response(200, """
                {"markdown":"# x","format":"csv","elapsed_ms":0.5,"input_bytes":11}
                """);
        newClient().convert(DOC, "a.csv");
        String sha = LAST_SHA256.get();
        assertTrue(sha != null && sha.matches("[0-9a-f]{64}"), "应为 64 位十六进制 SHA-256");
    }

    @Test
    @DisplayName("429 rate_limited → AnydocUnavailableException（可重试语义）")
    void rateLimitedMapped() {
        handler = body -> new Response(429,
                "{\"detail\":{\"code\":\"rate_limited\",\"reason\":\"exceeds 60 requests/minute\"}}");
        AnydocUnavailableException e = assertThrows(AnydocUnavailableException.class,
                () -> newClient().convert(DOC, "a.csv"));
        assertTrue(e.getMessage().contains("rate_limited") || e.getMessage().contains("exceeds"),
                "应携带服务端限流原因，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("批量转换：成功/业务拒绝混合，逐文档隔离且顺序一致")
    void batchIsolation() {
        AtomicInteger calls = new AtomicInteger();
        handler = body -> {
            int n = calls.incrementAndGet();
            // 偶数请求返回 415（业务拒绝），奇数成功
            if (n % 2 == 0) {
                return new Response(415, "{\"detail\":{\"code\":\"unsupported\"}}");
            }
            return new Response(200, """
                    {"markdown":"# ok","format":"csv","elapsed_ms":1.0,"input_bytes":5}
                    """);
        };
        List<InputDocument> docs = List.of(
                InputDocument.of("1".getBytes(StandardCharsets.UTF_8), "a.csv"),
                InputDocument.of("2".getBytes(StandardCharsets.UTF_8), "b.csv"),
                InputDocument.of("3".getBytes(StandardCharsets.UTF_8), "c.csv"),
                InputDocument.of("4".getBytes(StandardCharsets.UTF_8), "d.csv"));

        List<ConversionOutcome> outcomes = newClient().convertAll(docs, 1); // 串行保证顺序断言有效

        assertEquals(4, outcomes.size());
        assertTrue(outcomes.get(0).isSuccess());
        assertFalse(outcomes.get(1).isSuccess());
        assertInstanceOf(UnsupportedDocumentException.class, outcomes.get(1).error());
        assertTrue(outcomes.get(2).isSuccess());
        assertFalse(outcomes.get(3).isSuccess());
        assertEquals("b.csv", outcomes.get(1).input().filename());
    }

    @Test
    @DisplayName("批量转换：并行执行且全部成功")
    void batchParallel() {
        handler = body -> new Response(200, """
                {"markdown":"# ok","format":"csv","elapsed_ms":1.0,"input_bytes":5}
                """);
        List<InputDocument> docs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            docs.add(InputDocument.of(("doc" + i).getBytes(StandardCharsets.UTF_8), i + ".csv"));
        }
        List<ConversionOutcome> outcomes = newClient().convertAll(docs, 4);
        assertEquals(8, outcomes.size());
        assertTrue(outcomes.stream().allMatch(ConversionOutcome::isSuccess));
    }

    @Test
    @DisplayName("资产位置信息：placements 解析（标题链/块序号/前后语境）")
    void assetPlacementsParsed() {
        handler = body -> new Response(200, """
                {"markdown":"# 方案","format":"docx","elapsed_ms":2.0,"input_bytes":100,
                 "assets":[{
                   "id":0,"media_type":"image/png","origin_part":"word/media/image1.png",
                   "size":89,"truncated":false,"data_b64":"aGVsbG8=",
                   "placements":[
                     {"heading_path":["2026 方案","二、转换层选型"],"block_index":4,
                      "context_before":"架构图如下。","context_after":"图1：系统架构"},
                     {"heading_path":["2026 方案","三、预算"],"block_index":7,
                      "context_before":"三、预算","context_after":""}
                   ]}]}
                """);
        ConversionResult result = newClient().convert(DOC, "a.docx",
                ConvertOptions.defaults().withIncludeAssets(true));

        assertEquals(1, result.assets().size());
        AssetInfo asset = result.assets().get(0);
        assertEquals(2, asset.placements().size());

        AssetPlacement first = asset.primaryPlacement();
        assertEquals(List.of("2026 方案", "二、转换层选型"), first.headingPath());
        assertEquals(4, first.blockIndex());
        assertEquals("架构图如下。", first.contextBefore());
        assertEquals("图1：系统架构", first.anchorText());
        assertEquals("2026 方案 > 二、转换层选型", asset.primaryHeadingPath());

        AssetPlacement second = asset.placements().get(1);
        assertEquals("三、预算", second.anchorText(), "图后为空时应回退图前语境");
    }

    @Test
    @DisplayName("旧服务端无 placements 字段 → 空列表（向后兼容）")
    void assetPlacementsBackwardCompatible() {
        handler = body -> new Response(200, """
                {"markdown":"# 方案","format":"docx","elapsed_ms":2.0,"input_bytes":100,
                 "assets":[{"id":0,"media_type":"image/png","origin_part":"word/media/image1.png",
                            "size":89,"truncated":false,"data_b64":"aGVsbG8="}]}
                """);
        ConversionResult result = newClient().convert(DOC, "a.docx",
                ConvertOptions.defaults().withIncludeAssets(true));
        AssetInfo asset = result.assets().get(0);
        assertTrue(asset.placements() == null || asset.placements().isEmpty());
        assertEquals("", asset.primaryHeadingPath());
    }
}
