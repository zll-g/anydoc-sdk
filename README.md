# anydoc-sdk · anydoc-service Java SDK

> 企业级文档转换服务 [anydoc-service](#anydoc-service-服务部署) 的 Java 公共客户端 ——
> RAG 摄取管道中「任意格式文档 → Markdown」统一转换层的官方接入依赖包。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)]()

---

## 📖 项目介绍

在 RAG（检索增强生成）知识平台中，文档解析是数据质量的第一道关口。`anydoc-service`
是一个基于 [firecrawl-anydoc](https://github.com/firecrawl/anydoc) 的内网文档转换微服务
（14 种格式 → GitHub 风格 Markdown，含标题层级、表格、列表、内嵌图片资产），
而 **anydoc-sdk 是 Java 侧接入该服务的标准依赖包**，把「转换、资产提取、OCR、异步任务、
容错」封装成类型安全的 API，让业务工程几行代码即可接入企业级解析能力。

### 核心特性

| 能力 | 说明 |
|---|---|
| 📄 **文档转换** | docx/xlsx/csv/pptx/pdf/rtf/html 等 14 种格式 → Markdown，保留标题层级/表格/列表结构 |
| 🖼️ **资产提取 + 章节定位** | 内嵌图片随转换返回，附 `placements`（标题链 + 块序号 + 前后语境），解决"图片归属章节丢失"问题 |
| 🔍 **内嵌图片 OCR** | `withOcrAssets(true)` 动态开启服务端本地 OCR，资产携带 `ocrText`（数据不出域） |
| 📸 **独立 OCR API** | `ocr(bytes, filename)`：单张图片/PDF 直接识别文字，适合截图、扫描件补录 |
| ⏳ **异步任务** | 大文档/多页扫描走 `submitJob()` / `convertAsync()`，突破同步超时限制 |
| 🔁 **内建韧性** | 指数退避重试（仅瞬时故障）、**遵循服务端 Retry-After**、熔断器、请求超时、客户端在途合并（single-flight） |
| 🧩 **类型化错误契约** | 服务端状态码 → 类型化异常一一映射，用异常类型驱动 RAG 管道路由（死信/重试/告警） |
| 📦 **批量转换** | `convertAll()` 并发执行 + 逐文档故障隔离（历史文档迁移场景） |
| 🌱 **Spring Boot 3 自动装配** | 引入依赖即注入 `AnydocClient` Bean + Actuator 健康指示器（核心零 Spring 依赖，optional 引入） |
| 👁️ **可观测** | X-Request-ID 全链路透传、`ConversionListener` 指标钩子、内容指纹 `X-Content-SHA256` |

### 架构定位

```text
┌────────────────────┐   anydoc-sdk（本项目）   ┌─────────────────────┐
│  rag-ingestion     │ ───────────────────────▶ │   anydoc-service    │
│  （RAG 摄取服务）    │  转换/资产/OCR/异步任务      │  （文档转换微服务）     │
└────────────────────┘ ◀─────────────────────── └─────────────────────┘
        │                 Markdown + assets              │ 本地 OCR
        ▼                                                │ (PaddleOCR,
┌────────────────────┐                                   │  数据不出域)
│ image-enrichment   │                                   ▼
│ （图片 VLM 增强）     │                              PP-OCRv6 模型
└────────────────────┘
```

## 🛠️ 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 使用 `java.net.http.HttpClient`（HTTP/1.1 连接复用），**零第三方 HTTP 框架依赖** |
| Jackson | 2.17.x | JSON 序列化（唯一核心依赖之一） |
| SLF4J | 2.0.x | 日志门面 |
| Spring Boot | 3.3+（可选） | 自动装配 / 健康指示器，`optional` 依赖不传递污染非 Spring 使用方 |
| JUnit 5 | 5.10.x | 契约测试（JDK HttpServer 打桩服务端全部状态码） |

**服务端配套**：anydoc-service = Python 3.12 + FastAPI + firecrawl-anydoc（Rust 解析内核）
+ PaddleOCR/PP-OCRv6（本地 OCR，数据不出域）。

## 📁 目录结构

```text
anydoc-sdk/
├── pom.xml
├── README.md / LICENSE / CONTRIBUTING.md
└── src/main/java/io/github/zll/anydoc/
    ├── AnydocClient.java            # 公共接口（convert/ocr/submitJob/convertAsync/health…）
    ├── AnydocClientBuilder.java     # Builder（baseUrl/token/超时/重试/熔断/监听器）
    ├── DefaultAnydocClient.java     # 默认实现（韧性 + 单飞 + 错误映射）
    ├── ConvertOptions.java          # 请求选项（requestId/includeAssets/ocrAssets 动态开关）
    ├── ConversionResult.java        # 转换结果（markdown/assets/cacheHit/ocrApplied）
    ├── AssetInfo.java               # 资产（图片字节 + placements + ocrText）
    ├── AssetPlacement.java          # 资产章节定位（标题链/块序号/前后语境）
    ├── OcrResult.java               # 独立 OCR 结果
    ├── JobTicket.java / JobStatus.java  # 异步任务回执 / 状态快照
    ├── RetryPolicy.java             # 重试策略（指数退避；none/default）
    ├── CircuitBreaker.java          # 熔断器（滑窗失败率 + 冷却期）
    ├── ConversionListener.java      # 成功/失败指标钩子
    ├── exception/                   # 类型化异常契约（11 个）
    └── spring/                      # Spring Boot 自动装配（可选）
```

## 🚀 快速开始

### 1. 环境要求

- JDK 17+
- 一个可用的 anydoc-service 实例

### 2. 引入依赖

**Maven Central（推荐）**：

```xml
<dependency>
  <groupId>io.github.zll-g</groupId>
  <artifactId>anydoc-sdk</artifactId>
  <version>1.7.0</version>
</dependency>
```

**Gradle**：

```kotlin
implementation("io.github.zll-g:anydoc-sdk:1.7.0")
```

**或从源码构建**（未发布版本/二次开发）：

```bash
git clone https://github.com/zll-g/anydoc-sdk.git  
cd anydoc-sdk
mvn clean install
```

### 3. 最小示例

```java
AnydocClient anydoc = AnydocClient.builder()
        .baseUrl("http://localhost:8080")
        .token("your-service-token")          // 与服务端 ANYDOC_SERVICE_TOKEN 一致
        .build();

ConversionResult result = anydoc.convert(Files.readAllBytes(Path.of("report.docx")), "report.docx");
String markdown = result.markdown();          // GFM Markdown，可直接进分块器
```

## 📚 使用说明

### 客户端构建（Builder 参数）

```java
AnydocClient client = AnydocClient.builder()
        .baseUrl("http://anydoc-service:8080")          // 必填
        .token(System.getenv("ANYDOC_TOKEN"))           // 必填（生产勿用默认 dev-token）
        .connectTimeout(Duration.ofSeconds(2))          // 连接超时，默认 2s
        .requestTimeout(Duration.ofSeconds(10))         // 单请求超时，默认 10s
        .retry(RetryPolicy.exponential(3,               // 最多 3 次尝试
                Duration.ofMillis(300), 2.0))           // 300ms 起步 ×2 指数退避
        .circuitBreaker(CircuitBreaker.CircuitBreakerConfig.defaults())
        .listener(new MyMetricsListener())              // 指标钩子（可对接 Micrometer）
        .build();
```

### 同步转换 + 选项

```java
ConversionResult r = client.convert(bytes, "report.docx",
        ConvertOptions.defaults()
                .withRequestId(traceId)        // 链路追踪 ID（缺省自动生成）
                .withIncludeAssets(true)       // 返回内嵌图片资产 + 章节定位
                .withOcrAssets(true));         // v1.6：动态开启内嵌图片 OCR（服务端本地识别）

r.markdown();      // Markdown 正文
r.cacheHit();      // 是否命中服务端转换缓存
r.ocrApplied();    // 扫描 PDF 是否走了本地 OCR 兜底
for (AssetInfo a : r.assets()) {
    if (a.isImage() && a.hasData()) {
        byte[] img = a.data();                              // 图片字节（可送 VLM/对象存储）
        String section = a.primaryHeadingPath();            // "资源情况 > 1. web服务器"
        String ocrText = a.ocrText();                       // 图内文字（开启 ocrAssets 时）
    }
}
```

### 独立 OCR（单张图片 / PDF 直接识别）

```java
OcrResult ocr = client.ocr(screenshotBytes, "screenshot.png");
if (ocr.hasText()) {
    System.out.println(ocr.text());      // 多行以 \n 拼接
}
```

### 异步任务（大文档 / 多页扫描）

```java
// 一步到位：提交 + 轮询直至终态（失败按标准异常契约抛出）
ConversionResult r = client.convertAsync(bigPdfBytes, "big-scan.pdf",
        ConvertOptions.defaults().withIncludeAssets(true),
        Duration.ofMinutes(10),          // 最长等待
        Duration.ofSeconds(1));          // 轮询间隔

// 或手动控制：
JobTicket ticket = client.submitJob(bytes, "big.pdf", ConvertOptions.defaults());
JobStatus status = client.jobStatus(ticket.jobId());
if (status.isSucceeded())  { /* status.result() */ }
if (status.isFailed())     { /* status.errorCode() / status.retryable() */ }
```

### 批量转换（历史文档迁移）

```java
List<InputDocument> docs = ...;
List<ConversionOutcome> outcomes = client.convertAll(docs, 8);   // 并发度 8
for (ConversionOutcome o : outcomes) {
    if (o.isSuccess()) { /* o.result() */ } else { /* o.error()：逐文档故障隔离，不中断批量 */ }
}
```

### 异常契约与管道路由

服务端状态码 → 类型化异常一一映射；**415/422 属业务结果而非故障**，重试策略与熔断器
已自动将其排除在失败计数之外：

| 异常 | 服务端 | 语义 | RAG 管道建议 |
|---|---|---|---|
| `UnauthorizedException` | 401 | Token 错误 | 配置错误，直接暴露给运维 |
| `DocumentTooLargeException` | 413 | 超过大小上限 | 拒绝上传 |
| `UnsupportedDocumentException` | 415 | 未知格式 / OCR 引擎不可用 | `isOcrUnavailable()` 为 true 时属部署问题：缓冲待重放；否则死信 |
| `EncryptedDocumentException` | 422 | 加密文档 | 隔离并通知所有者 |
| `CorruptedDocumentException` | 422 | 损坏/缺部件/触发安全上限 | 死信 + 告警，不重试 |
| `AnydocTimeoutException` | 504 / 客户端超时 | 转换超时 | 重试一次后转兜底队列 |
| `AnydocUnavailableException` | 429 / 503 | 限流/过载/停机（携带 Retry-After） | SDK 已按 `max(退避, Retry-After)` 自动重试 |
| `AnydocCircuitOpenException` | — | 熔断冷却期 | 快速失败，转兜底队列 |
| `AnydocServiceException` | 5xx / 网络 | 瞬时故障 | 自动重试 → 熔断 → 兜底 |

```java
try {
    return client.convert(bytes, filename, opts);
} catch (UnsupportedDocumentException e) {
    if (e.isOcrUnavailable()) { /* 部署缺陷：告警 + 缓冲重放 */ }
    else { /* 文档本身不支持：死信 */ }
} catch (EncryptedDocumentException e) { /* 隔离 */ }
catch (CorruptedDocumentException e)   { /* 死信 + 告警 */ }
catch (DocumentTooLargeException e)    { /* 拒绝 */ }
catch (AnydocServiceException e)       { /* 已重试仍失败：兜底队列 */ }
```

### Spring Boot 自动装配

引入依赖后，通过 `rag.anydoc.*` 配置即自动注入 `AnydocClient` Bean 与 Actuator 健康指示器：

```yaml
rag:
  anydoc:
    base-url: ${ANYDOC_BASE_URL:http://localhost:8080}
    token: ${ANYDOC_SERVICE_TOKEN}
    connect-timeout: 2s
    request-timeout: 10s
    retry:
      max-attempts: 3
      initial-backoff: 300ms
      multiplier: 2.0
    circuit:
      enabled: true
      window-size: 20
      failure-rate-threshold: 0.5
      open-duration: 10s
    health:
      enabled: true        # /actuator/health 出现 anydoc 项
```

```java
@Service
public class IngestionService {
    private final AnydocClient anydoc;      // 构造注入即可
    public IngestionService(AnydocClient anydoc) { this.anydoc = anydoc; }
}
```

## 🖥️ anydoc-service 服务部署

SDK 需连接一个运行中的 anydoc-service。以官方镜像方式发布，
部署只需拉取/导入镜像并配置环境变量。

### 服务概览

| 项 | 说明 |
|---|---|
| 技术栈 | Python 3.12 + FastAPI + firecrawl-anydoc 0.1.8（Rust 内核）+ PaddleOCR/PP-OCRv6 |
| 端口 | 8080 |
| API | `POST /v1/convert`（同步转换）· `POST /v1/jobs` / `GET /v1/jobs/{id}`（异步任务）· `POST /v1/ocr`（独立 OCR）· `/healthz` · `/readyz` · `/metrics`（Prometheus） |
| 认证 | Bearer Token（`ANYDOC_SERVICE_TOKEN`，与 SDK `token` 一致） |
| 版本配套 | anydoc-sdk ≥1.7.0 需服务端 ≥1.7.0（异步任务）；低版本服务端仅同步/OCR 能力可用 |

### 获取镜像

镜像默认内置本地 OCR 能力（PaddleOCR/PP-OCRv6，约 2~3GB），两种获取方式：

```bash
# 方式 A：从镜像仓库拉取（有网环境；地址以实际发布为准）
docker pull <registry>/anydoc-service:1.7.0

# 方式 B：离线导入（内网环境：由服务方提供镜像 tar 包）
docker load -i anydoc-service-1.7.0.tar
```

### 最简运行（Docker Desktop）

```bash
docker run -d --name anydoc-service \
  -p 8080:8080 \
  -e ANYDOC_SERVICE_TOKEN=<强随机Token> \
  --memory 2g --cpus 2 \
  --restart unless-stopped \
  anydoc-service:1.7.0
```

> ⚠️ **内存 ≥ 2GB**：PP-OCRv6 medium 模型推理常驻约 1~1.5GB。
> Docker Desktop 请在 Settings → Resources 将 VM 内存调到 ≥ 4GB。

验证：

```bash
curl http://localhost:8080/healthz
curl http://localhost:8080/readyz     # ocr.available=true, warm=true 即 OCR 就绪
curl -H "Authorization: Bearer <Token>" -F "file=@test.docx" http://localhost:8080/v1/convert
```

### 生产部署要点（docker-compose / Kubernetes）

docker run 生产加固示例（只读根文件系统、能力收敛、资源限制）：

```bash
docker run -d --name anydoc-service -p 8080:8080 \
  -e ANYDOC_SERVICE_TOKEN=<强随机Token> \
  --memory 2g --cpus 2 \
  --read-only --tmpfs /tmp:size=128m \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --log-opt max-size=10m --log-opt max-file=3 \
  --restart unless-stopped \
  <registry>/anydoc-service:1.7.0
```

Kubernetes 部署关键配置：

- **资源**：`memory limit ≥ 2Gi`（OCR 模型推理常驻 1~1.5GB）、CPU limit ≈ `ANYDOC_MAX_CONCURRENCY`
- **探针**：`livenessProbe → /healthz`，`readinessProbe → /readyz`
  （就绪探针在 OCR 模型预加载完成前不放行流量；停机期返回 503 自动摘流）
- **优雅停机**：`terminationGracePeriodSeconds: 30`（≥ 服务端 drain 窗口 20s）
- **安全**：非 root、`readOnlyRootFilesystem` + /tmp emptyDir、drop ALL capabilities、
  NetworkPolicy 仅放行摄取服务来源
- **Token**：以 Secret 注入 `ANYDOC_SERVICE_TOKEN`

### 离线 / 内网部署（无外网）

```bash
# 1) 在有网机器预下载 PP-OCRv6 模型（二选一）：
#    a. 直接拷贝任一运行过服务的实例的模型缓存目录：~/.paddlex/official_models/
#    b. 用 huggingface 命令行下载：
pip install -U "huggingface_hub[cli]"
hf download PaddlePaddle/PP-OCRv6_medium_det --local-dir ./ocr-models/PP-OCRv6_medium_det
hf download PaddlePaddle/PP-OCRv6_medium_rec --local-dir ./ocr-models/PP-OCRv6_medium_rec

# 2) 内网挂载并指定离线模型目录（PaddleOCR 直读本地文件，全程不触网）
docker run -d -p 8080:8080 \
  -v /data/ocr-models:/opt/ocr-models:ro \
  -e ANYDOC_OCR_MODEL_DIR=/opt/ocr-models \
  -e ANYDOC_SERVICE_TOKEN=<Token> \
  <registry>/anydoc-service:1.7.0

# 3) 验证：readyz 显示 ocr.available=true 且 source=offline:/opt/ocr-models
```

### 关键配置项（服务端环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANYDOC_SERVICE_TOKEN` | `dev-token` | Bearer Token，**生产必须改为强随机值** |
| `ANYDOC_MAX_BYTES` | 50MB | 上传大小上限 |
| `ANYDOC_MAX_CONCURRENCY` / `ANYDOC_QUEUE_WAIT_SECONDS` | 8 / 10s | 并发上限 / 排队超时（503 背压） |
| `ANYDOC_CONVERT_TIMEOUT_SECONDS` | 30s | 同步转换超时（504） |
| `ANYDOC_OCR_ENABLED` | true | 本地 OCR 兜底总开关（扫描 PDF） |
| `ANYDOC_OCR_MODEL_DIR` | 空 | 离线模型目录（设置后全程不触网） |
| `ANYDOC_OCR_DEVICE` | cpu | 推理设备：cpu / gpu / gpu:0（gpu 需 paddlepaddle-gpu + CUDA 镜像） |
| `ANYDOC_OCR_PRELOAD` | true | 启动预加载模型（readyz 加载完成前不放行流量） |
| `ANYDOC_OCR_ASSETS_ENABLED` | false | 内嵌图片 OCR 全局默认（可被请求参数 `ocr_assets` 动态覆盖） |
| `ANYDOC_ASYNC_WORKERS` / `ANYDOC_ASYNC_QUEUE_SIZE` | 2 / 16 | 异步任务线程数 / 队列上限 |
| `ANYDOC_ASYNC_JOB_TIMEOUT_SECONDS` | 600s | 异步任务执行超时 |
| `ANYDOC_DRAIN_TIMEOUT_SECONDS` | 20s | 优雅停机 drain 窗口（编排层 grace period 需 ≥ 该值） |
| `ANYDOC_CACHE_ENABLED` / `ANYDOC_RATE_LIMIT_RPM` | true / 0 | 转换缓存 / Token 级限流 |

## 📡 可观测与运维

- **指标**（服务端 `/metrics`，Prometheus 格式）：`anydoc_convert_total`、
  `anydoc_duration_seconds`、`anydoc_jobs_total`、`anydoc_ocr_total`、
  `anydoc_cache_hits_total`、`anydoc_singleflight_shares_total` 等；
- **SDK 侧**：`ConversionListener` 钩子（onSuccess/onFailure，含耗时与异常类型）
  可对接 Micrometer；X-Request-ID 与服务端日志（`request_id` 字段）一一对账；
- **健康检查**：`/healthz`（存活）/ `/readyz`（就绪：OCR 可用性、模型 warm 状态、
  停机 draining 状态）；Spring Boot 自动装配含 `AnydocHealthIndicator`。


## 📄 许可证

[Apache License 2.0](LICENSE) © zll-g
