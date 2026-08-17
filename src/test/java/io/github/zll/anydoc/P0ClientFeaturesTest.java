package io.github.zll.anydoc;

import com.sun.net.httpserver.HttpServer;
import io.github.zll.anydoc.exception.AnydocUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0 客户端能力测试：客户端单飞去重 + Retry-After 遵循。 */
class P0ClientFeaturesTest {

    private static HttpServer server;
    private static volatile Handler handler;
    private static final AtomicInteger SERVER_CALLS = new AtomicInteger();

    @FunctionalInterface
    private interface Handler {
        Response handle(String body) throws Exception;
    }

    private record Response(int status, String body, int retryAfterSeconds) {
    }

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert", exchange -> {
            SERVER_CALLS.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response resp;
            try {
                resp = handler.handle(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted", e);
            } catch (Exception e) {
                throw new java.io.IOException("handler error", e);
            }
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (resp.retryAfterSeconds() > 0) {
                exchange.getResponseHeaders().add("Retry-After", String.valueOf(resp.retryAfterSeconds()));
            }
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

    private AnydocClient client(RetryPolicy retry) {
        return AnydocClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("t")
                .retry(retry)
                .circuitBreaker(CircuitBreaker.CircuitBreakerConfig.disabled())
                .build();
    }

    private static final String SUCCESS = """
            {"markdown":"# ok","format":"csv","elapsed_ms":1.0,"input_bytes":5,"cache_hit":false}
            """;

    @Test
    @DisplayName("客户端单飞：5 个并发相同内容调用，仅 1 次真实 HTTP 请求")
    void clientSingleFlight() throws Exception {
        SERVER_CALLS.set(0);
        handler = body -> {
            Thread.sleep(400);   // 制造重叠窗口，确保并发调用落在同一在途键上
            return new Response(200, SUCCESS, 0);
        };
        AnydocClient c = client(RetryPolicy.none());
        byte[] content = "single-flight-content".getBytes(StandardCharsets.UTF_8);

        int n = 5;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<ConversionResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return c.convert(content, "same.csv");
            }));
        }
        ready.await();
        go.countDown();   // 同时放行，确保并发重叠
        for (Future<ConversionResult> f : futures) {
            assertTrue(f.get().markdown().contains("ok"));
        }
        pool.shutdown();

        assertEquals(1, SERVER_CALLS.get(), "相同内容并发调用应只产生 1 次真实请求");
    }

    @Test
    @DisplayName("Retry-After 遵循：429 后按服务端退避时长重试成功")
    void retryAfterHonored() {
        SERVER_CALLS.set(0);
        AtomicInteger calls = new AtomicInteger();
        handler = body -> {
            if (calls.incrementAndGet() == 1) {
                return new Response(429,
                        "{\"detail\":{\"code\":\"rate_limited\",\"reason\":\"rpm\"}}", 1);
            }
            return new Response(200, SUCCESS, 0);
        };
        AnydocClient c = client(RetryPolicy.of(3, Duration.ofMillis(50), 2.0));

        long start = System.nanoTime();
        ConversionResult result = c.convert("retry-content".getBytes(StandardCharsets.UTF_8), "a.csv");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.markdown().contains("ok"));
        assertEquals(2, SERVER_CALLS.get(), "应重试 1 次后成功");
        assertTrue(elapsedMs >= 900, "应遵循 Retry-After=1s 退避，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("503 Retry-After 同样被遵循（背压场景）")
    void retryAfterOn503() {
        SERVER_CALLS.set(0);
        AtomicInteger calls = new AtomicInteger();
        handler = body -> {
            if (calls.incrementAndGet() == 1) {
                return new Response(503,
                        "{\"detail\":{\"code\":\"overloaded\",\"reason\":\"queue full\"}}", 1);
            }
            return new Response(200, SUCCESS, 0);
        };
        AnydocClient c = client(RetryPolicy.of(2, Duration.ofMillis(50), 2.0));

        ConversionResult result = c.convert("bp-content".getBytes(StandardCharsets.UTF_8), "b.csv");
        assertTrue(result.markdown().contains("ok"));
        assertEquals(2, SERVER_CALLS.get());
    }
}
