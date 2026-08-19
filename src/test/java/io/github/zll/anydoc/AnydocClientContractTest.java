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
        server.createContext("/v1/pdf/render", exchange -> {
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
                                   "input_bytes":3,"assets":[],"cache_hit":false}}
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
    @DisplayName("v1.8：renderPdf -> 200 解析位图（base64 自动解码）+ 选项查询串")
    void renderPdfWithOptions() throws IOException {
        respondJson(200, """
                {"total_pages":3,"rendered":1,"scale":1.5,
                 "pages":[{"page":1,"width":900,"height":1200,
                           "media_type":"image/png","data_b64":"iVBORw0KGgo="}],
                 "input_bytes":1000,"elapsed_ms":12.5}
                """);
        PdfRenderResult r = client.renderPdf(DOC,
                RenderOptions.defaults().withPages("1").withScale(1.5).withFormat("png"));
        assertEquals(3, r.totalPages());
        assertEquals(1, r.rendered());
        PdfRenderResult.RenderedPage p = r.pages().get(0);
        assertEquals(1, p.page());
        assertTrue(p.isPng());
        assertTrue(p.data().length > 0);
        String path = LAST_REQUEST.get().path();
        assertTrue(path.contains("pages=1") && path.contains("scale=1.5")
                && path.contains("format=png"), path);
    }

    @Test
    @DisplayName("v1.8：renderPdf 非 PDF 输入 -> 415 UnsupportedDocumentException")
    void renderPdfUnsupported() {
        respondJson(415, """
                {"detail":{"code":"unsupported","reason":"PDF 解析失败"}}
                """);
        assertThrows(UnsupportedDocumentException.class, () -> client.renderPdf(DOC));
    }

    @Test
    @DisplayName("v2.2：withMaxPages -> 查询串携带 max_pages")
    void maxPagesQuery() {
        respondJson(200, """
                {"markdown":"x","format":"pdf","elapsed_ms":3.0,"input_bytes":3,
                 "assets":[],"cache_hit":false}
                """);
        client.convert(DOC, "a.pdf", ConvertOptions.defaults().withMaxPages(50));
        assertTrue(LAST_REQUEST.get().path().contains("max_pages=50"), LAST_REQUEST.get().path());
        // 缺省不携带（保持服务端默认值）
        client.convert(DOC, "b.pdf", ConvertOptions.defaults());
        assertTrue(!LAST_REQUEST.get().path().contains("max_pages"), LAST_REQUEST.get().path());
    }

    @Test
    @DisplayName("v2.6：withHeadersFooters -> 查询串携带 headers_footers（缺省不携带）")
    void headersFootersQuery() {
        respondJson(200, """
                {"markdown":"x","format":"docx","elapsed_ms":3.0,"input_bytes":3,
                 "assets":[],"cache_hit":false}
                """);
        client.convert(DOC, "a.docx", ConvertOptions.defaults().withHeadersFooters(false));
        String path = LAST_REQUEST.get().path();
        assertTrue(path.contains("headers_footers=false"), path);
        assertTrue(ConvertOptions.defaults().withHeadersFooters(false)
                .variantFingerprint().contains("hf=false"));
        // 缺省不携带（跟随服务端开关）
        client.convert(DOC, "b.docx", ConvertOptions.defaults());
        assertTrue(!LAST_REQUEST.get().path().contains("headers_footers"),
                LAST_REQUEST.get().path());
    }

    @Test
    @DisplayName("v2.6：响应 headers_footers/headers_footers_stripped -> 结构化解析")
    void headersFootersParsing() {
        respondJson(200, """
                {"markdown":"正文","format":"docx","elapsed_ms":3.0,"input_bytes":3,
                 "assets":[],"cache_hit":false,
                 "headers_footers":[
                   {"kind":"header","scope":"default","location":"center",
                    "text":"ACME 科技 · 机密","sections":[0],"source":"docx"},
                   {"kind":"header","scope":"default","location":"image","text":"公司徽标",
                    "sections":[0],"source":"docx",
                    "image":{"origin_part":"word/media/image1.png",
                             "media_type":"image/png","alt":"公司徽标",
                             "size":8,"data_b64":"QUJDREVGR0g=","truncated":false}},
                   {"kind":"footer","scope":"default","location":"center",
                    "text":"文档编号 ACME-2026-001 · 第 {PAGE} 页","sections":[0],"source":"docx"}],
                 "headers_footers_stripped":12}
                """);
        ConversionResult r = client.convert(DOC, "a.docx", ConvertOptions.defaults());
        assertTrue(r.hasHeadersFooters());
        assertEquals(3, r.headersFooters().size());
        HeaderFooterInfo hdr = r.headersFooters().get(0);
        assertTrue(hdr.isHeader());
        assertEquals("center", hdr.location());
        assertEquals("ACME 科技 · 机密", hdr.text());
        // v2.7/v2.8：页眉图片结构化引用（含字节与大小）
        HeaderFooterInfo img = r.headersFooters().get(1);
        assertEquals("image", img.location());
        assertEquals("word/media/image1.png", img.image().originPart());
        assertEquals("image/png", img.image().mediaType());
        assertEquals("公司徽标", img.image().alt());
        assertEquals(8, img.image().size());
        assertTrue(img.image().hasData());
        assertArrayEquals("ABCDEFGH".getBytes(StandardCharsets.UTF_8), img.image().data());
        assertTrue(!img.image().isTruncated());
        assertTrue(r.headersFooters().get(2).isFooter());
        assertEquals(12, r.headersFootersStripped());
        assertEquals(3, r.headerFooterTexts().size());
        // pdf 页码型条目解析
        respondJson(200, """
                {"markdown":"正文","format":"pdf","elapsed_ms":3.0,"input_bytes":3,
                 "assets":[],"cache_hit":false,
                 "headers_footers":[
                   {"kind":"footer","scope":"all","location":"bottom","text":"Page 1 of 6",
                    "pages_seen":6,"page_frequency":1.0,"page_number":true,"source":"pdf"}],
                 "headers_footers_stripped":6}
                """);
        ConversionResult p = client.convert(DOC, "b.pdf", ConvertOptions.defaults());
        assertTrue(p.headersFooters().get(0).isPageNumber());
        assertEquals(6, p.headersFooters().get(0).pagesSeen());
        assertEquals(1.0, p.headersFooters().get(0).pageFrequency());
    }

    @Test
    @DisplayName("v2.1：withUseCache/withPages -> 查询串携带 use_cache/pages")
    void dynamicCacheAndPagesQuery() {
        respondJson(200, """
                {"markdown":"x","format":"pdf","elapsed_ms":3.0,"input_bytes":3,
                 "assets":[],"cache_hit":false}
                """);
        client.convert(DOC, "a.pdf", ConvertOptions.defaults()
                .withUseCache(false).withPages("0-2"));
        String path = LAST_REQUEST.get().path();
        assertTrue(path.contains("use_cache=false"), path);
        assertTrue(path.contains("pages=0-2"), path);
    }

    @Test
    @DisplayName("v2.0：withTimeoutSeconds -> 查询串携带 timeout")
    void timeoutQueryParam() {
        respondJson(200, """
                {"markdown":"x","format":"pdf","elapsed_ms":3.0,"input_bytes":3,
                 "assets":[],"cache_hit":false}
                """);
        client.convert(DOC, "a.pdf", ConvertOptions.defaults().withTimeoutSeconds(600));
        assertTrue(LAST_REQUEST.get().path().contains("timeout=600"), LAST_REQUEST.get().path());
    }

    @Test
    @DisplayName("v2.0：不设置动态参数 -> 查询串不携带（保持服务端默认）")
    void dynamicOcrParamsAbsent() {
        respondJson(200, """
                {"markdown":"x","format":"docx","elapsed_ms":1.0,"input_bytes":3,
                 "assets":[],"cache_hit":false}
                """);
        client.convert(DOC, "a.docx", ConvertOptions.defaults());
        assertEquals("/v1/convert", LAST_REQUEST.get().path());
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
