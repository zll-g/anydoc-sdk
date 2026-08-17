package io.github.zll.anydoc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.zll.anydoc.exception.AnydocUnavailableException;
import io.github.zll.anydoc.exception.UnsupportedDocumentException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 重试语义测试：瞬时故障重试、业务拒绝不重试、重试耗尽抛出最终异常。
 */
class RetryBehaviorTest {

    private static HttpServer server;
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();
    private static volatile Handler handler;

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert", exchange -> {
            exchange.getRequestBody().readAllBytes();
            handler.handle(exchange);
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void send(HttpExchange exchange, int status, String body, boolean retryAfter)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter) {
            exchange.getResponseHeaders().add("Retry-After", "1");
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    private AnydocClient client(RetryPolicy policy) {
        return AnydocClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .retry(policy)
                .build();
    }

    @Test
    @DisplayName("503 两次后成功：共尝试 3 次，最终返回结果")
    void retriesTransientThenSucceeds() {
        ATTEMPTS.set(0);
        handler = exchange -> {
            int attempt = ATTEMPTS.incrementAndGet();
            if (attempt <= 2) {
                send(exchange, 503, "{\"detail\":{\"code\":\"overloaded\"}}", true);
            } else {
                send(exchange, 200,
                        "{\"markdown\":\"ok\",\"format\":\"csv\",\"elapsed_ms\":1.0,\"input_bytes\":5}", false);
            }
        };
        AnydocClient c = client(RetryPolicy.of(3, Duration.ofMillis(1), 1.0));

        ConversionResult result = c.convert("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), "t.csv");

        assertEquals("ok", result.markdown());
        assertEquals(3, ATTEMPTS.get(), "应恰好尝试 3 次");
    }

    @Test
    @DisplayName("重试耗尽：始终 503 时抛出最终异常且尝试次数 = maxAttempts")
    void exhaustsRetries() {
        ATTEMPTS.set(0);
        handler = exchange -> {
            ATTEMPTS.incrementAndGet();
            send(exchange, 503, "{\"detail\":{\"code\":\"overloaded\"}}", true);
        };
        AnydocClient c = client(RetryPolicy.of(4, Duration.ofMillis(1), 1.0));

        assertThrows(AnydocUnavailableException.class,
                () -> c.convert("x".getBytes(StandardCharsets.UTF_8), "t.csv"));
        assertEquals(4, ATTEMPTS.get());
    }

    @Test
    @DisplayName("业务拒绝不重试：415 只请求 1 次")
    void businessErrorsAreNotRetried() {
        ATTEMPTS.set(0);
        handler = exchange -> {
            ATTEMPTS.incrementAndGet();
            send(exchange, 415, "{\"detail\":{\"code\":\"unsupported\"}}", false);
        };
        AnydocClient c = client(RetryPolicy.of(5, Duration.ofMillis(1), 1.0));

        assertThrows(UnsupportedDocumentException.class,
                () -> c.convert("x".getBytes(StandardCharsets.UTF_8), "scan.pdf"));
        assertEquals(1, ATTEMPTS.get(), "业务拒绝禁止重试");
    }
}
