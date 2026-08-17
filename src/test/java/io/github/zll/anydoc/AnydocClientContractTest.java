package io.github.zll.anydoc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.zll.anydoc.exception.AnydocException;
import io.github.zll.anydoc.exception.AnydocServiceException;
import io.github.zll.anydoc.exception.AnydocTimeoutException;
import io.github.zll.anydoc.exception.AnydocUnavailableException;
import io.github.zll.anydoc.exception.CorruptedDocumentException;
import io.github.zll.anydoc.exception.DocumentTooLargeException;
import io.github.zll.anydoc.exception.EncryptedDocumentException;
import io.github.zll.anydoc.exception.UnauthorizedException;
import io.github.zll.anydoc.exception.UnsupportedDocumentException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误契约测试：用 JDK HttpServer 打桩 anydoc-service，
 * 验证 SDK 对服务端每个契约状态码的异常映射。
 */
class AnydocClientContractTest {

    private static HttpServer server;
    private static AnydocClient client;

    /** 最近一次请求的快照（头 + 体），供断言使用。 */
    private static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();

    private record CapturedRequest(String authorization, String requestId, String contentType,
                                   String path, byte[] body) {
    }

    private static volatile Responder responder;

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            LAST_REQUEST.set(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Request-ID"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestURI().toString(),
                    body));
            responder.respond(exchange);
        });
        server.createContext("/v1/ocr", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            LAST_REQUEST.set(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Request-ID"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestURI().toString(),
                    body));
            responder.respond(exchange);
        });
        server.createContext("/v1/jobs", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            LAST_REQUEST.set(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Request-ID"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestURI().toString(),
                    body));
            responder.respond(exchange);
        });
        server.start();

        client = AnydocClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .retry(RetryPolicy.none())   // 契约测试不掺入重试变量
                .build();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        LAST_REQUEST.set(null);
    }

    private static void respondJson(int status, String json) {
        responder = exchange -> {
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 503) {
                exchange.getResponseHeaders().add("Retry-After", "3");
            }
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        };
    }

    private static final byte[] DOC = "hello anydoc".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("200：成功解析 ConversionResult，且请求携带认证/追踪头与 multipart 内容")
    void successConversion() {
        respondJson(200, """
                {"markdown":"# 标题\\n\\n正文","format":"docx","elapsed_ms":4.4,"input_bytes":12}
                """);

        ConversionResult result = client.convert(DOC, "report.docx", "req-001");

        assertEquals("# 标题\n\n正文", result.markdown());
        assertEquals("docx", result.format());
        assertEquals(4.4, result.elapsedMs(), 0.001);
        assertEquals(12, result.inputBytes());
        assertEquals("req-001", result.requestId());

        CapturedRequest captured = LAST_REQUEST.get();
        assertNotNull(captured);
        assertEquals("Bearer test-token", captured.authorization());
        assertEquals("req-001", captured.requestId());
        assertTrue(captured.contentType().startsWith("multipart/form-data; boundary="));
        String body = new String(captured.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("filename=\"report.docx\""), "multipart 应携带文件名");
        assertTrue(body.contains("hello anydoc"), "multipart 应携带文档内容");
    }

    @Test
    @DisplayName("includeAssets：请求携带查询参数，响应资产 base64 自动解码为字节")
    void conversionWithAssets() {
        respondJson(200, """
                {"markdown":"正文","format":"docx","elapsed_ms":2.0,"input_bytes":10,
                 "assets":[{"id":0,"media_type":"image/png","origin_part":"word/media/image1.png",
                             "size":5,"truncated":false,"data_b64":"aGVsbG8="}]}
                """);

        ConversionResult result = client.convert(DOC, "img.docx",
                ConvertOptions.defaults().withIncludeAssets(true));

        assertTrue(LAST_REQUEST.get().path().contains("include_assets=true"),
                "includeAssets=true 应以查询参数下发");
        assertEquals(1, result.assets().size());
        assertEquals(1, result.enrichableAssetCount());
        AssetInfo asset = result.assets().get(0);
        assertTrue(asset.isImage());
        assertTrue(asset.hasData());
        assertEquals("word/media/image1.png", asset.originPart());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), asset.data());
    }

    @Test
    @DisplayName("未请求资产：assets 为空列表（向后兼容）")
    void noAssetsWhenNotRequested() {
        respondJson(200, """
                {"markdown":"正文","format":"docx","elapsed_ms":1.0,"input_bytes":5}
                """);
        ConversionResult result = client.convert(DOC, "a.docx");
        assertNotNull(result.assets());
        assertTrue(result.assets().isEmpty());
    }

    @Test
    @DisplayName("401 -> UnauthorizedException")
    void unauthorized() {
        respondJson(401, """
                {"detail":{"code":"unauthorized"}}
                """);
        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> client.convert(DOC, "a.docx"));
        assertEquals(401, e.httpStatus());
        assertTrue(!e.isRetryable());
    }

    @Test
    @DisplayName("413 -> DocumentTooLargeException")
    void tooLarge() {
        respondJson(413, """
                {"detail":{"code":"too_large","reason":"request body exceeds 52428800 bytes"}}
                """);
        DocumentTooLargeException e = assertThrows(DocumentTooLargeException.class,
                () -> client.convert(DOC, "big.docx"));
        assertEquals(413, e.httpStatus());
        assertTrue(e.getMessage().contains("52428800"));
    }

    @Test
    @DisplayName("415 -> UnsupportedDocumentException（扫描件 → OCR 兜底信号）")
    void unsupported() {
        respondJson(415, """
                {"detail":{"code":"unsupported","reason":"unknown_format"}}
                """);
        UnsupportedDocumentException e = assertThrows(UnsupportedDocumentException.class,
                () -> client.convert(DOC, "scan.pdf"));
        assertEquals(415, e.httpStatus());
        assertEquals("unsupported", e.errorCode());
    }

    @Test
    @DisplayName("422 encrypted -> EncryptedDocumentException")
    void encrypted() {
        respondJson(422, """
                {"detail":{"code":"encrypted","reason":"password protected"}}
                """);
        assertThrows(EncryptedDocumentException.class, () -> client.convert(DOC, "sec.docx"));
    }

    @Test
    @DisplayName("422 malformed/missing_part/resource_limit -> CorruptedDocumentException 且保留错误码")
    void corrupted() {
        respondJson(422, """
                {"detail":{"code":"resource_limit","reason":"decompression budget exceeded"}}
                """);
        CorruptedDocumentException e = assertThrows(CorruptedDocumentException.class,
                () -> client.convert(DOC, "bomb.docx"));
        assertEquals("resource_limit", e.errorCode());
    }

    @Test
    @DisplayName("503 -> AnydocUnavailableException 且携带 Retry-After")
    void overloaded() {
        respondJson(503, """
                {"detail":{"code":"overloaded","reason":"conversion queue full"}}
                """);
        AnydocUnavailableException e = assertThrows(AnydocUnavailableException.class,
                () -> client.convert(DOC, "a.docx"));
        assertEquals(3, e.retryAfterSeconds());
        assertTrue(e.isRetryable());
    }

    @Test
    @DisplayName("504 -> AnydocTimeoutException")
    void serverTimeout() {
        respondJson(504, """
                {"detail":{"code":"timeout","reason":"conversion timed out"}}
                """);
        assertThrows(AnydocTimeoutException.class, () -> client.convert(DOC, "a.docx"));
    }

    @Test
    @DisplayName("500 -> AnydocServiceException（可重试语义）")
    void serverError() {
        respondJson(500, """
                {"detail":{"code":"conversion_failed"}}
                """);
        AnydocServiceException e = assertThrows(AnydocServiceException.class,
                () -> client.convert(DOC, "a.docx"));
        assertEquals(500, e.httpStatus());
        assertTrue(e.isRetryable());
    }

    @Test
    @DisplayName("v1.6：ConvertOptions.withOcrAssets(true) -> 请求携带 ocr_assets=true")
    void convertWithOcrAssetsQueryParam() {
        respondJson(200, """
                {"markdown":"x","format":"docx","elapsed_ms":1.0,"input_bytes":3,"assets":[]}
                """);
        client.convert(DOC, "a.docx",
                ConvertOptions.defaults().withIncludeAssets(true).withOcrAssets(true));
        String path = LAST_REQUEST.get().path();
        assertTrue(path.contains("include_assets=true"), path);
        assertTrue(path.contains("ocr_assets=true"), path);

        // 显式关闭同样下发；不调用则不携带该参数（跟随服务端全局配置）
        client.convert(DOC, "a.docx", ConvertOptions.defaults().withOcrAssets(false));
        assertTrue(LAST_REQUEST.get().path().contains("ocr_assets=false"));
        client.convert(DOC, "a.docx", ConvertOptions.defaults());
        assertTrue(!LAST_REQUEST.get().path().contains("ocr_assets"),
                LAST_REQUEST.get().path());
    }

    @Test
    @DisplayName("v1.6：ocr() 独立 OCR -> 200 解析 text/kind")
    void ocrHappyPath() {
        respondJson(200, """
                {"text":"Total: 16000.50","kind":"image","input_bytes":123,"elapsed_ms":4.5}
                """);
        OcrResult result = client.ocr(new byte[]{1, 2, 3}, "img.png", "rid-ocr");
        assertEquals("Total: 16000.50", result.text());
        assertEquals("image", result.kind());
        assertTrue(result.hasText());
        assertEquals("rid-ocr", result.requestId());
        assertTrue(LAST_REQUEST.get().path().startsWith("/v1/ocr"));
        assertEquals("Bearer test-token", LAST_REQUEST.get().authorization());
    }

    @Test
    @DisplayName("v1.6：ocr() 415 ocr_unavailable -> UnsupportedDocumentException.isOcrUnavailable()")
    void ocrUnavailable() {
        respondJson(415, """
                {"detail":{"code":"ocr_unavailable","reason":"引擎不可用"}}
                """);
        UnsupportedDocumentException e = assertThrows(UnsupportedDocumentException.class,
                () -> client.ocr(new byte[]{1}, "img.png"));
        assertTrue(e.isOcrUnavailable());
    }

    @Test
    @DisplayName("v1.7：convertAsync 提交+轮询 -> succeeded 解析结果")
    void asyncJobHappyPath() throws IOException {
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        responder = exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String json;
            if ("/v1/jobs".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                status = 202;
                json = "{\"job_id\":\"j-1\",\"status\":\"queued\"}";
            } else if (polls.incrementAndGet() == 1) {
                json = "{\"job_id\":\"j-1\",\"status\":\"running\",\"format\":\"docx\"}";
            } else {
                json = """
                        {"job_id":"j-1","status":"succeeded","format":"docx",
                         "result":{"markdown":"# ok","format":"docx","elapsed_ms":3.0,
                                   "input_bytes":3,"assets":[],"cache_hit":false,"ocr_applied":false}}
                        """;
            }
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        };
        ConversionResult result = client.convertAsync(DOC, "a.docx", ConvertOptions.defaults(),
                java.time.Duration.ofSeconds(10), java.time.Duration.ofMillis(20));
        assertEquals("# ok", result.markdown());
        assertTrue(LAST_REQUEST.get().path().startsWith("/v1/jobs"));
    }

    @Test
    @DisplayName("v1.7：任务失败 -> 按错误码映射类型化异常（missing_part -> Corrupted）")
    void asyncJobFailureMapping() throws IOException {
        responder = exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String json;
            if ("/v1/jobs".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                status = 202;
                json = "{\"job_id\":\"j-2\",\"status\":\"queued\"}";
            } else {
                json = """
                        {"job_id":"j-2","status":"failed","format":"docx",
                         "error":{"code":"missing_part","reason":"missing document.xml","retryable":false}}
                        """;
            }
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        };
        assertThrows(CorruptedDocumentException.class,
                () -> client.convertAsync(DOC, "a.docx", ConvertOptions.defaults(),
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(20)));
    }

    @Test
    @DisplayName("v1.7：jobStatus 404 -> AnydocException(job_not_found)")
    void jobNotFound() {
        respondJson(404, """
                {"detail":{"code":"job_not_found","reason":"任务不存在或已过期"}}
                """);
        AnydocException e = assertThrows(AnydocException.class, () -> client.jobStatus("nope"));
        assertEquals(404, e.httpStatus());
    }

    @Test
    @DisplayName("health()：解析 /healthz 响应")
    void health() {
        responder = null; // 健康检查走独立 context 之外的简单桩：直接复用 convert 不合适，这里用真实响应
        // 打桩 /healthz
        server.createContext("/healthz", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = "{\"status\":\"ok\",\"anydoc\":\"0.1.8\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        ServiceInfo info = client.health();
        assertTrue(info.isOk());
        assertEquals("0.1.8", info.details().get("anydoc"));
    }
}
