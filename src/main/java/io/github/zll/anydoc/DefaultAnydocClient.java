package io.github.zll.anydoc;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.zll.anydoc.exception.AnydocException;
import io.github.zll.anydoc.exception.AnydocServiceException;
import io.github.zll.anydoc.exception.AnydocTimeoutException;
import io.github.zll.anydoc.exception.AnydocUnavailableException;
import io.github.zll.anydoc.exception.CorruptedDocumentException;
import io.github.zll.anydoc.exception.DocumentTooLargeException;
import io.github.zll.anydoc.exception.EncryptedDocumentException;
import io.github.zll.anydoc.exception.UnauthorizedException;
import io.github.zll.anydoc.exception.UnsupportedDocumentException;
import io.github.zll.anydoc.internal.JsonSupport;
import io.github.zll.anydoc.internal.MultipartBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.zll.anydoc.CircuitBreaker.CircuitBreakerConfig;
import io.github.zll.anydoc.exception.AnydocCircuitOpenException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link AnydocClient} 默认实现。
 *
 * <p>线程安全：持有不可变配置 + 共享 JDK HttpClient 连接池，可安全地在多线程间复用。
 */
final class DefaultAnydocClient implements AnydocClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultAnydocClient.class);

    private final URI baseUri;
    private final URI convertUri;
    private final URI ocrUri;
    private final URI jobsUri;
    private final URI healthUri;
    private final String token;
    private final Duration requestTimeout;
    private final RetryPolicy retryPolicy;
    private final List<ConversionListener> listeners;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker;
    /** 客户端在途合并：相同内容并发调用共享同一次转换（键=内容 SHA-256+文件名+资产开关）。 */
    private final ConcurrentHashMap<String, CompletableFuture<ConversionResult>> inflight =
            new ConcurrentHashMap<>();

    DefaultAnydocClient(URI baseUri, String token, Duration connectTimeout, Duration requestTimeout,
                        RetryPolicy retryPolicy, List<ConversionListener> listeners, HttpClient customClient,
                        CircuitBreakerConfig circuitBreakerConfig) {
        this.baseUri = baseUri;
        this.convertUri = baseUri.resolve("/v1/convert");
        this.ocrUri = baseUri.resolve("/v1/ocr");
        this.jobsUri = baseUri.resolve("/v1/jobs");
        this.healthUri = baseUri.resolve("/healthz");
        this.token = token;
        this.requestTimeout = requestTimeout;
        this.retryPolicy = retryPolicy;
        this.listeners = listeners;
        this.httpClient = customClient != null ? customClient : HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.circuitBreaker = new CircuitBreaker(
                circuitBreakerConfig != null ? circuitBreakerConfig : CircuitBreakerConfig.defaults());
    }

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    @Override
    public ConversionResult convert(byte[] content, String filename) {
        return convert(content, filename, ConvertOptions.defaults());
    }

    @Override
    public ConversionResult convert(byte[] content, String filename, String requestId) {
        return convert(content, filename, ConvertOptions.defaults().withRequestId(requestId));
    }

    @Override
    public ConversionResult convert(byte[] content, String filename, ConvertOptions options) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content 不能为空");
        }
        ConvertOptions opts = options == null ? ConvertOptions.defaults() : options;
        String fname = (filename == null || filename.isBlank()) ? "document.bin" : filename.trim();
        String rid = (opts.requestId() == null || opts.requestId().isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : opts.requestId().trim();

        // ---- 客户端单飞：相同内容的并发调用共享同一次转换，避免重复网络往返 ----
        // 键含 ocr_assets 开关：开/关产物不同，不可共享结果
        String sfKey = sha256Hex(content) + ":" + fname + ":" + opts.includeAssets()
                + ":" + opts.ocrAssets();
        CompletableFuture<ConversionResult> fresh = new CompletableFuture<>();
        CompletableFuture<ConversionResult> existing = inflight.putIfAbsent(sfKey, fresh);
        if (existing != null) {
            // 已有同内容转换在途：共享其结果（重建为本调用方的 requestId）
            try {
                ConversionResult shared = existing.get();
                return new ConversionResult(shared.markdown(), shared.format(), shared.elapsedMs(),
                        shared.inputBytes(), rid, shared.assets(), shared.cacheHit(), shared.ocrApplied());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AnydocException("等待在途转换被中断", e, null, "interrupted", rid);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new AnydocException("在途转换失败", e, null, "unexpected", rid);
            }
        }

        // 本调用是属主：执行带熔断 + 重试的转换，完成后通知共享方
        try {
            ConversionResult result = convertWithResilience(content, fname, rid, opts);
            fresh.complete(result);
            return result;
        } catch (RuntimeException e) {
            fresh.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(sfKey);
        }
    }

    /** 熔断 + 重试（含 Retry-After 遵循）的转换执行。 */
    private ConversionResult convertWithResilience(byte[] content, String fname, String rid, ConvertOptions opts) {
        long startNanos = System.nanoTime();

        // 熔断检查：服务端近期失败率过高时快速失败，避免持续施压
        if (!circuitBreaker.tryAcquire()) {
            AnydocCircuitOpenException open = new AnydocCircuitOpenException(
                    "熔断器开启：anydoc-service 近期失败率过高，冷却期内拒绝调用 [requestId=" + rid + "]");
            fireFailure(rid, fname, open, startNanos);
            throw open;
        }

        int maxAttempts = retryPolicy.maxAttempts();
        AnydocServiceException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ConversionResult result = doConvert(content, fname, rid, opts);
                circuitBreaker.recordSuccess();
                fireSuccess(rid, fname, result, startNanos);
                return result;
            } catch (AnydocServiceException e) {
                // 瞬时故障：按策略退避重试
                lastFailure = e;
                if (attempt < maxAttempts) {
                    Duration backoff = retryPolicy.backoff(attempt);
                    // Retry-After 遵循：服务端 429/503 明示的退避时长优先（取两者较大值）
                    if (e instanceof AnydocUnavailableException unavailable
                            && unavailable.retryAfterSeconds() > 0) {
                        Duration retryAfter = Duration.ofSeconds(unavailable.retryAfterSeconds());
                        if (retryAfter.compareTo(backoff) > 0) {
                            backoff = retryAfter;
                        }
                    }
                    log.warn("anydoc 转换瞬时失败，第 {}/{} 次尝试，{}ms 后重试 [requestId={}, cause={}]",
                            attempt, maxAttempts, backoff.toMillis(), rid, e.getMessage());
                    sleep(backoff);
                }
            } catch (AnydocException e) {
                // 业务拒绝/配置错误：不重试、不计入熔断，直接路由
                fireFailure(rid, fname, e, startNanos);
                throw e;
            }
        }
        circuitBreaker.recordFailure();   // 重试耗尽的瞬时故障计入熔断窗口
        fireFailure(rid, fname, lastFailure, startNanos);
        throw lastFailure;
    }

    // ------------------------------------------------------------------
    // 批量转换
    // ------------------------------------------------------------------

    @Override
    public List<ConversionOutcome> convertAll(List<InputDocument> documents, int parallelism) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int poolSize = Math.max(1, Math.min(parallelism, documents.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "anydoc-batch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<ConversionOutcome>> futures = new ArrayList<>(documents.size());
            for (InputDocument doc : documents) {
                futures.add(pool.submit(() -> {
                    try {
                        ConversionResult result = convert(doc.content(), doc.filename(), doc.toOptions());
                        return ConversionOutcome.success(doc, result);
                    } catch (AnydocException e) {
                        return ConversionOutcome.failure(doc, e);   // 逐文档故障隔离
                    }
                }));
            }
            List<ConversionOutcome> outcomes = new ArrayList<>(futures.size());
            for (Future<ConversionOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AnydocException("批量转换被中断", e, null, "interrupted", null);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof AnydocException ae) {
                        throw ae;
                    }
                    throw new AnydocException("批量转换异常: " + cause, e, null, "unexpected", null);
                }
            }
            return outcomes;
        } finally {
            pool.shutdown();
        }
    }

    /** 动态拼接转换 URI：include_assets + ocr_assets（v1.6 请求级动态开关）。 */
    private URI convertUriFor(boolean includeAssets, Boolean ocrAssets) {
        StringBuilder q = new StringBuilder();
        if (includeAssets) {
            q.append("include_assets=true");
        }
        if (ocrAssets != null) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append("ocr_assets=").append(ocrAssets);
        }
        return q.length() == 0 ? convertUri : baseUri.resolve("/v1/convert?" + q);
    }

    private ConversionResult doConvert(byte[] content, String filename, String rid, ConvertOptions opts) {
        MultipartBody body = MultipartBody.of("file", filename, content);
        HttpRequest request = HttpRequest.newBuilder(convertUriFor(opts.includeAssets(), opts.ocrAssets()))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-ID", rid)
                .header("X-Content-SHA256", sha256Hex(content))   // 内容指纹：审计 + 服务端缓存亲和
                .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                .header("Accept", "application/json")
                .POST(body.publisher())
                .build();
        HttpResponse<byte[]> response = send(request, rid);
        return handleConvertResponse(response, rid);
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 独立 OCR（v1.6）：对单张图片/PDF 直接调用服务端本地 OCR
    // ------------------------------------------------------------------

    @Override
    public OcrResult ocr(byte[] content, String filename) {
        return ocr(content, filename, null);
    }

    @Override
    public OcrResult ocr(byte[] content, String filename, String requestId) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String fname = (filename == null || filename.isBlank()) ? "image.png" : filename.trim();
        String rid = (requestId == null || requestId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : requestId.trim();

        // 熔断 + 重试（含 Retry-After 遵循），与转换同策略；OCR 结果无状态，不做单飞
        if (!circuitBreaker.tryAcquire()) {
            throw new AnydocCircuitOpenException(
                    "熔断器开启：anydoc-service 近期失败率过高，冷却期内拒绝调用 [requestId=" + rid + "]");
        }
        int maxAttempts = retryPolicy.maxAttempts();
        AnydocServiceException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                OcrResult result = doOcr(content, fname, rid);
                circuitBreaker.recordSuccess();
                return result;
            } catch (AnydocServiceException e) {
                lastFailure = e;
                if (attempt < maxAttempts) {
                    Duration backoff = retryPolicy.backoff(attempt);
                    if (e instanceof AnydocUnavailableException unavailable
                            && unavailable.retryAfterSeconds() > 0) {
                        Duration retryAfter = Duration.ofSeconds(unavailable.retryAfterSeconds());
                        if (retryAfter.compareTo(backoff) > 0) {
                            backoff = retryAfter;
                        }
                    }
                    sleep(backoff);
                }
            }
        }
        circuitBreaker.recordFailure();
        throw lastFailure;
    }

    private OcrResult doOcr(byte[] content, String filename, String rid) {
        MultipartBody body = MultipartBody.of("file", filename, content);
        HttpRequest request = HttpRequest.newBuilder(ocrUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-ID", rid)
                .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                .header("Accept", "application/json")
                .POST(body.publisher())
                .build();
        HttpResponse<byte[]> response = send(request, rid);
        return handleOcrResponse(response, rid);
    }

    private OcrResult handleOcrResponse(HttpResponse<byte[]> response, String rid) {
        int status = response.statusCode();
        byte[] body = response.body();
        switch (status) {
            case 200 -> {
                OcrResponse parsed = parse(body, OcrResponse.class, rid);
                return new OcrResult(parsed.text(), parsed.kind(), parsed.inputBytes(),
                        parsed.elapsedMs(), rid);
            }
            case 401 ->
                throw new UnauthorizedException(errorReason(body, "Token 缺失或错误，请检查配置"), rid);
            case 413 ->
                throw new DocumentTooLargeException(errorReason(body, "文件超过服务端大小上限"), rid);
            case 415 ->
                // 含 ocr_unavailable（引擎不可用）与 unsupported（输入类型不支持）
                throw new UnsupportedDocumentException(
                        errorReason(body, "OCR 引擎不可用或输入不支持"), errorCode(body), rid);
            case 422 ->
                throw new CorruptedDocumentException(
                        errorReason(body, "OCR 识别失败（输入可能损坏）"), errorCode(body), rid);
            case 429 ->
                throw new AnydocUnavailableException(
                        errorReason(body, "服务端限流（RPM 超限）"), retryAfterSeconds(response), rid);
            case 503 ->
                throw new AnydocUnavailableException(
                        errorReason(body, "服务端过载（背压）"), retryAfterSeconds(response), rid);
            case 504 ->
                throw new AnydocTimeoutException(errorReason(body, "服务端 OCR 超时"), rid);
            default -> {
                if (status >= 500) {
                    throw new AnydocServiceException("服务端故障: HTTP " + status, null, status,
                            "http_" + status, rid);
                }
                throw new AnydocException("非预期响应: HTTP " + status, null, status,
                        "unexpected", rid);
            }
        }
    }

    // ------------------------------------------------------------------
    // 异步任务（v1.7）：submitJob / jobStatus / convertAsync
    // ------------------------------------------------------------------

    @Override
    public JobTicket submitJob(byte[] content, String filename, ConvertOptions options) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content 不能为空");
        }
        ConvertOptions opts = options == null ? ConvertOptions.defaults() : options;
        String fname = (filename == null || filename.isBlank()) ? "document.bin" : filename.trim();
        String rid = (opts.requestId() == null || opts.requestId().isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : opts.requestId().trim();
        MultipartBody body = MultipartBody.of("file", fname, content);
        HttpRequest request = HttpRequest.newBuilder(jobsUriFor(opts.includeAssets(), opts.ocrAssets()))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-ID", rid)
                .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                .header("Accept", "application/json")
                .POST(body.publisher())
                .build();
        HttpResponse<byte[]> response = send(request, rid);
        int status = response.statusCode();
        byte[] respBody = response.body();
        if (status == 202) {
            JobSubmitResponse parsed = parse(respBody, JobSubmitResponse.class, rid);
            return new JobTicket(parsed.jobId(), parsed.status(),
                    Boolean.TRUE.equals(parsed.cacheHit()));
        }
        // 提交阶段的错误与同步转换同契约（401/413/415/429/503/504/5xx）
        throw mapSubmitError(status, respBody, response, rid);
    }

    @Override
    public JobStatus jobStatus(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        String rid = UUID.randomUUID().toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/jobs/" + jobId))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-ID", rid)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request, rid);
        int status = response.statusCode();
        byte[] body = response.body();
        if (status == 200) {
            JobStatusResponse parsed = parse(body, JobStatusResponse.class, rid);
            ConversionResult result = null;
            if (parsed.result() != null) {
                result = new ConversionResult(parsed.result().markdown(), parsed.result().format(),
                        parsed.result().elapsedMs(), parsed.result().inputBytes(), rid,
                        parsed.result().assets() == null ? List.of() : List.copyOf(parsed.result().assets()),
                        Boolean.TRUE.equals(parsed.result().cacheHit()),
                        Boolean.TRUE.equals(parsed.result().ocrApplied()));
            }
            String errCode = parsed.error() == null ? null : parsed.error().code();
            String errReason = parsed.error() == null ? null : parsed.error().reason();
            boolean retryable = parsed.error() != null && Boolean.TRUE.equals(parsed.error().retryable());
            return new JobStatus(parsed.jobId(), parsed.status(), parsed.format(),
                    parsed.createdAt(), parsed.startedAt(), parsed.finishedAt(),
                    result, errCode, errReason, retryable);
        }
        if (status == 404) {
            throw new AnydocException("任务不存在或已过期: " + errorReason(body, "job_not_found"),
                    null, 404, "job_not_found", rid);
        }
        if (status == 401) {
            throw new UnauthorizedException(errorReason(body, "Token 缺失或错误，请检查配置"), rid);
        }
        if (status >= 500) {
            throw new AnydocServiceException("服务端故障: HTTP " + status, null, status,
                    "http_" + status, rid);
        }
        throw new AnydocException("非预期响应: HTTP " + status, null, status, "unexpected", rid);
    }

    @Override
    public ConversionResult convertAsync(byte[] content, String filename, ConvertOptions options,
                                         Duration maxWait, Duration pollInterval) {
        JobTicket ticket = submitJob(content, filename, options);
        long deadlineNanos = System.nanoTime() + maxWait.toNanos();
        Duration interval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                ? Duration.ofSeconds(1) : pollInterval;
        while (true) {
            JobStatus st = jobStatus(ticket.jobId());
            if (st.isSucceeded()) {
                return st.result();
            }
            if (st.isFailed()) {
                throw mapJobFailure(st, ticket.jobId());
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new AnydocTimeoutException(
                        "异步转换等待超时（" + maxWait + "），任务仍在执行: jobId=" + ticket.jobId(),
                        ticket.jobId());
            }
            sleep(interval);
        }
    }

    /** 任务失败 → 与同步转换一致的类型化异常（供调用方按既有 catch 分支处理）。 */
    private RuntimeException mapJobFailure(JobStatus st, String rid) {
        String code = st.errorCode() == null ? "conversion_failed" : st.errorCode();
        String reason = st.errorReason() == null ? "异步任务失败" : st.errorReason();
        return switch (code) {
            case "unsupported", "ocr_unavailable" ->
                    new UnsupportedDocumentException(reason, code, rid);
            case "encrypted" -> new EncryptedDocumentException(reason, rid);
            case "malformed", "missing_part", "resource_limit" ->
                    new CorruptedDocumentException(reason, code, rid);
            case "timeout" -> new AnydocTimeoutException(reason, rid);
            case "server_shutting_down", "overloaded", "rate_limited" ->
                    new AnydocUnavailableException(reason, 0, rid);
            default -> new AnydocServiceException(reason, null, null, code, rid);
        };
    }

    private RuntimeException mapSubmitError(int status, byte[] body,
                                            HttpResponse<byte[]> response, String rid) {
        return switch (status) {
            case 401 -> new UnauthorizedException(errorReason(body, "Token 缺失或错误，请检查配置"), rid);
            case 413 -> new DocumentTooLargeException(errorReason(body, "文档超过服务端大小上限"), rid);
            case 415 -> new UnsupportedDocumentException(
                    errorReason(body, "未知格式或 OCR 引擎不可用"), errorCode(body), rid);
            case 429 -> new AnydocUnavailableException(
                    errorReason(body, "服务端限流（RPM 超限）"), retryAfterSeconds(response), rid);
            case 503 -> new AnydocUnavailableException(
                    errorReason(body, "服务端过载或停机（背压）"), retryAfterSeconds(response), rid);
            case 504 -> new AnydocTimeoutException(errorReason(body, "服务端超时"), rid);
            default -> {
                if (status >= 500) {
                    yield new AnydocServiceException("服务端故障: HTTP " + status, null, status,
                            "http_" + status, rid);
                }
                yield new AnydocException("非预期响应: HTTP " + status, null, status,
                        "unexpected", rid);
            }
        };
    }

    /** 拼接 /v1/jobs 查询串（include_assets + ocr_assets，与转换路径同规则）。 */
    private URI jobsUriFor(boolean includeAssets, Boolean ocrAssets) {
        StringBuilder q = new StringBuilder();
        if (includeAssets) {
            q.append("include_assets=true");
        }
        if (ocrAssets != null) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append("ocr_assets=").append(ocrAssets);
        }
        return q.length() == 0 ? jobsUri : baseUri.resolve("/v1/jobs?" + q);
    }

    private HttpResponse<byte[]> send(HttpRequest request, String rid) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new AnydocTimeoutException("请求超时: " + request.uri(), e, rid);
        } catch (IOException e) {
            throw new AnydocServiceException("网络异常: " + e.getMessage(), e, null, "network", rid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnydocException("调用被中断", e, null, "interrupted", rid);
        }
    }

    private ConversionResult handleConvertResponse(HttpResponse<byte[]> response, String rid) {
        int status = response.statusCode();
        byte[] body = response.body();

        switch (status) {
            case 200 -> {
                ConvertResponse parsed = parse(body, ConvertResponse.class, rid);
                List<AssetInfo> assets = parsed.assets() == null
                        ? List.of() : List.copyOf(parsed.assets());
                return new ConversionResult(parsed.markdown(), parsed.format(),
                        parsed.elapsedMs(), parsed.inputBytes(), rid, assets,
                        Boolean.TRUE.equals(parsed.cacheHit()),
                        Boolean.TRUE.equals(parsed.ocrApplied()));
            }
            case 401 -> {
                throw new UnauthorizedException(errorReason(body, "Token 缺失或错误，请检查配置"), rid);
            }
            case 413 -> {
                throw new DocumentTooLargeException(errorReason(body, "文档超过服务端大小上限"), rid);
            }
            case 415 -> {
                throw new UnsupportedDocumentException(
                        errorReason(body, "未知格式或扫描件（建议转 OCR 兜底）"), errorCode(body), rid);
            }
            case 422 -> {
                String code = errorCode(body);
                String reason = errorReason(body, "文档被服务端拒绝");
                switch (code) {
                    case "encrypted" ->
                            throw new EncryptedDocumentException(reason, rid);
                    case "malformed", "missing_part", "resource_limit" ->
                            throw new CorruptedDocumentException(reason, code, rid);
                    default ->
                            // 未知 422 码按损坏文档处理，保持“不重试”语义
                            throw new CorruptedDocumentException(reason, code, rid);
                }
            }
            case 429 -> {
                // 服务端限流（Token 级 RPM）：按 Retry-After 退避，语义同过载
                throw new AnydocUnavailableException(
                        errorReason(body, "服务端限流（RPM 超限）"), retryAfterSeconds(response), rid);
            }
            case 503 -> {
                throw new AnydocUnavailableException(
                        errorReason(body, "服务端过载（背压）"), retryAfterSeconds(response), rid);
            }
            case 504 -> {
                throw new AnydocTimeoutException(errorReason(body, "服务端转换超时"), rid);
            }
            default -> {
                if (status >= 500) {
                    throw new AnydocServiceException("服务端故障: HTTP " + status, null, status,
                            "http_" + status, rid);
                }
                throw new AnydocException("非预期响应: HTTP " + status, null, status,
                        "unexpected", rid);
            }
        }
    }

    // ------------------------------------------------------------------
    // 健康检查
    // ------------------------------------------------------------------

    @Override
    public ServiceInfo health() {
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request, "health");
        if (response.statusCode() != 200) {
            throw new AnydocServiceException("健康检查失败: HTTP " + response.statusCode(),
                    null, response.statusCode(), "health_failed", "health");
        }
        Map<String, Object> details = parse(response.body(), Map.class, "health");
        Object status = details.get("status");
        return new ServiceInfo(status == null ? "" : status.toString(), Map.copyOf(details));
    }

    @Override
    public void close() {
        // JDK HttpClient 无需显式关闭；保留接口便于未来资源扩展
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private <T> T parse(byte[] body, Class<T> type, String rid) {
        try {
            return JsonSupport.mapper().readValue(body, type);
        } catch (IOException e) {
            throw new AnydocException("响应解析失败: " + e.getMessage(), e, null, "bad_response", rid);
        }
    }

    /** 解析服务端错误体 {"detail":{"code":...,"reason":...}}，非法 JSON 时返回 unknown。 */
    private ErrorBody parseError(byte[] body) {
        try {
            ErrorBody parsed = JsonSupport.mapper().readValue(body, ErrorBody.class);
            return parsed != null ? parsed : new ErrorBody(null);
        } catch (IOException e) {
            return new ErrorBody(null);
        }
    }

    private String errorCode(byte[] body) {
        ErrorDetail detail = parseError(body).detail();
        return detail != null && detail.code() != null ? detail.code() : "unknown";
    }

    private String errorReason(byte[] body, String fallback) {
        ErrorDetail detail = parseError(body).detail();
        if (detail != null && detail.reason() != null && !detail.reason().isBlank()) {
            return detail.reason();
        }
        return fallback;
    }

    private static int retryAfterSeconds(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Integer.parseInt(v.trim());
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                })
                .orElse(-1);
    }

    private void fireSuccess(String rid, String filename, ConversionResult result, long startNanos) {
        if (listeners.isEmpty()) {
            return;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        for (ConversionListener listener : listeners) {
            try {
                listener.onSuccess(rid, filename, result, elapsed);
            } catch (RuntimeException e) {
                log.warn("ConversionListener.onSuccess 抛出异常（已忽略）", e);
            }
        }
    }

    private void fireFailure(String rid, String filename, AnydocException error, long startNanos) {
        if (listeners.isEmpty()) {
            return;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        for (ConversionListener listener : listeners) {
            try {
                listener.onFailure(rid, filename, error, elapsed);
            } catch (RuntimeException e) {
                log.warn("ConversionListener.onFailure 抛出异常（已忽略）", e);
            }
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 服务端响应模型 ----

    private record ConvertResponse(
            @JsonProperty("markdown") String markdown,
            @JsonProperty("format") String format,
            @JsonProperty("elapsed_ms") double elapsedMs,
            @JsonProperty("input_bytes") int inputBytes,
            @JsonProperty("assets") List<AssetInfo> assets,
            @JsonProperty("cache_hit") Boolean cacheHit,
            @JsonProperty("ocr_applied") Boolean ocrApplied) {
    }

    private record OcrResponse(
            @JsonProperty("text") String text,
            @JsonProperty("kind") String kind,
            @JsonProperty("input_bytes") int inputBytes,
            @JsonProperty("elapsed_ms") double elapsedMs) {
    }

    private record JobSubmitResponse(
            @JsonProperty("job_id") String jobId,
            @JsonProperty("status") String status,
            @JsonProperty("cache_hit") Boolean cacheHit) {
    }

    private record JobError(
            @JsonProperty("code") String code,
            @JsonProperty("reason") String reason,
            @JsonProperty("retryable") Boolean retryable) {
    }

    private record JobStatusResponse(
            @JsonProperty("job_id") String jobId,
            @JsonProperty("status") String status,
            @JsonProperty("format") String format,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("finished_at") String finishedAt,
            @JsonProperty("result") ConvertResponse result,
            @JsonProperty("error") JobError error) {
    }

    private record ErrorBody(@JsonProperty("detail") ErrorDetail detail) {
    }

    private record ErrorDetail(@JsonProperty("code") String code, @JsonProperty("reason") String reason) {
    }
}
