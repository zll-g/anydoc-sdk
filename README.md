# anydoc-sdk · anydoc-service Java SDK

> 企业级文档转换服务 [anydoc-service](#anydoc-service-服务部署) 的 Java 公共客户端 ——
> RAG 摄取管道中「任意格式文档 → Markdown」统一转换层的官方接入依赖包。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)]()
[![Maven](https://img.shields.io/badge/io.github.zll--g-anydoc--sdk%202.0.0-green.svg)]()

---

## 📖 项目介绍

在 RAG（检索增强生成）知识平台中，文档解析是数据质量的第一道关口。anydoc-service
是一个基于 [firecrawl-anydoc](https://github.com/firecrawl/anydoc) 的内网文档转换微服务
（14 种格式 → GitHub 风格 Markdown，含标题层级、表格、列表、内嵌图片资产），
而 **anydoc-sdk 是 Java 侧接入该服务的标准依赖包**，把「转换、资产提取、异步任务、
容错」封装成类型安全的 API，让业务工程几行代码即可接入企业级解析能力。

> **v2.8 能力边界**：SDK 与服务端均**不含 OCR 与 pdf-inspector 页级判定**；
> 无文本层的扫描 PDF 按 `415 unsupported`（`UnsupportedDocumentException`）契约处理，
> 扫描件请在上游由专门的 OCR 服务预处理后再进入本管道。

### 核心特性

| 能力 | 说明 |
|---|---|
| 📄 **文档转换** | docx/xlsx/csv/pptx/pdf/rtf/html 等 14 种格式 → Markdown，保留标题层级/表格/列表结构 |
| 🖼️ **资产提取 + 章节定位** | 内嵌图片随转换返回，附 `placements`（标题链 + 块序号 + 前后语境），解决“图片归属章节丢失”问题 |
| 🖨️ **PDF 页面渲染** | `renderPdf(bytes, options)`：服务端 pypdfium2 位图渲染（预览/抽检/向 VLM 供图） |
| ⏳ **异步任务** | 大文档走 `submitJob()` / `convertAsync()`，突破同步超时限制 |
| ⏱️ **请求级动态超时** | `withTimeoutSeconds(n)` 按文档体量覆盖服务端默认预算 |
| 🔁 **内建韧性** | 指数退避重试（仅瞬时故障）、**遵循服务端 Retry-After**、熔断器、请求超时、客户端在途合并（single-flight）；**服务端 504 不重试**（对方已耗尽预算，应转异步通道） |
| 🧩 **类型化错误契约** | 服务端状态码 → 类型化异常一一映射，用异常类型驱动 RAG 管道路由（死信/重试/告警） |
| 📦 **批量转换** | `convertAll()` 并发执行 + 逐文档故障隔离（历史文档迁移场景） |
| 🌱 **Spring Boot 3 自动装配** | 引入依赖即注入 `AnydocClient` Bean + Actuator 健康指示器（核心零 Spring 依赖，optional 引入） |
| 👁️ **可观测** | X-Request-ID 全链路透传、`ConversionListener` 指标钩子、内容指纹 `X-Content-SHA256` |

### 架构定位

```text
┌────────────────────┐      anydoc-sdk（本项目）      ┌─────────────────────┐
│  rag-ingestion     │ ─────────────────────────────▶ │   anydoc-service    │
│  （RAG 摄取服务）    │   转换 / 资产 / 渲染 / 异步任务   │  （文档转换微服务）     │
└────────────────────┘ ◀───────────────────────────── └─────────────────────┘
        │                 Markdown + assets
        ▼
┌────────────────────┐
│ image-enrichment   │
│ （图片 VLM 增强）     │
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
+ pypdfium2（页面渲染），详见 [服务部署](#anydoc-service-服务部署)。

## 📁 目录结构

```text
anydoc-sdk/
├── pom.xml
├── README.md / LICENSE / CONTRIBUTING.md
└── src/main/java/io/github/zll/anydoc/
    ├── AnydocClient.java            # 公共接口（convert/renderPdf/submitJob/convertAsync/health…）
    ├── AnydocClientBuilder.java     # Builder（baseUrl/token/超时/重试/熔断/监听器）
    ├── DefaultAnydocClient.java     # 默认实现（韧性 + 单飞 + 错误映射）
    ├── ConvertOptions.java          # 请求选项（requestId/includeAssets/timeoutSeconds）
    ├── ConversionResult.java        # 转换结果（markdown/assets/cacheHit）
    ├── AssetInfo.java               # 资产（图片字节 + placements）
    ├── AssetPlacement.java          # 资产章节定位（标题链/块序号/前后语境）
    ├── PdfRenderResult.java         # 页面渲染结果（位图字节 + 尺寸）
    ├── RenderOptions.java           # 渲染选项（页选择/倍率/格式）
    ├── JobTicket.java / JobStatus.java  # 异步任务回执 / 状态快照
    ├── RetryPolicy.java             # 重试策略（指数退避；none/default）
    ├── CircuitBreaker.java          # 熔断器（滑窗失败率 + 冷却期）
    ├── ConversionListener.java      # 成功/失败指标钩子
    ├── exception/                   # 类型化异常契约
    └── spring/                      # Spring Boot 自动装配（可选）
```

## 🚀 快速开始

### 1. 环境要求

- JDK 17+
- 一个可用的 anydoc-service 实例（[部署指引](#anydoc-service-服务部署)）

### 2. 引入依赖

**Maven Central（推荐）**：

```xml
<dependency>
  <groupId>io.github.zll-g</groupId>
  <artifactId>anydoc-sdk</artifactId>
  <version>2.8.0</version>
</dependency>
```

**Gradle**：

```kotlin
implementation("io.github.zll-g:anydoc-sdk:2.8.0")
```

**或从源码构建**（未发布版本/二次开发）：

```bash
git clone https://github.com/zll-g/anydoc-sdk.git
cd anydoc-sdk
mvn clean install
```

> 企业内网可改用私服镜像同步（Nexus/Artifactory 代理 Maven Central）。

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
        .requestTimeout(Duration.ofSeconds(30))         // 单请求超时，默认 30s
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
                .withTimeoutSeconds(600));     // 请求级动态超时（大文档放宽预算）

r.markdown();      // Markdown 正文
r.cacheHit();      // 是否命中服务端转换缓存
for (AssetInfo a : r.assets()) {
    if (a.isImage() && a.hasData()) {
        byte[] img = a.data();                              // 图片字节（可送 VLM/对象存储）
        String section = a.primaryHeadingPath();            // "资源情况 > 1. web服务器"
    }
}
```

> 设置 `withTimeoutSeconds` 时，SDK 自动把 HTTP 读取超时放宽到该值 + 10s，
> 确保能收到服务端的 504 响应而非客户端先断连。

### PDF 页面渲染（pypdfium2）

```java
// 预览/可视化抽检/向 VLM 供图
PdfRenderResult rr = client.renderPdf(pdfBytes,
        RenderOptions.defaults().withPages("0,2").withScale(1.5).withFormat("png"));
byte[] page0png = rr.pages().get(0).data();
```

服务端对应 `POST /v1/pdf/render`（页选择 `pages=0,2` 或区间 `1-3`；倍率钳制
[0.5, 4.0]；单次最多 `ANYDOC_RENDER_MAX_PAGES` 页）。

### 异步任务（大文档 / 批量迁移）

```java
// 一步到位：提交 + 轮询直至终态（失败按标准异常契约抛出）
ConversionResult r = client.convertAsync(bigPdfBytes, "big.pdf",
        ConvertOptions.defaults().withIncludeAssets(true),
        Duration.ofMinutes(10),          // 最长等待
        Duration.ofSeconds(2));          // 轮询间隔

// 或手动控制：
JobTicket ticket = client.submitJob(bytes, "big.pdf", ConvertOptions.defaults());
JobStatus status = client.jobStatus(ticket.jobId());
if (status.isSucceeded()) { /* status.result() */ }
if (status.isFailed())    { /* status.errorCode() / status.retryable() */ }
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
| `UnsupportedDocumentException` | 415 | 未知格式 / **无文本层扫描 PDF**（v2.0 无 OCR） | 死信 + 人工复核；扫描件由上游 OCR 服务预处理 |
| `EncryptedDocumentException` | 422 | 加密文档 | 隔离并通知所有者 |
| `CorruptedDocumentException` | 422 | 损坏/缺部件/触发安全上限 | 死信 + 告警，不重试 |
| `AnydocTimeoutException` | 504 / 客户端超时 | 转换超时 | **客户端超时**：自动重试（single-flight 共享在途转换）；**服务端 504**：不重试，转 `convertAsync()` 异步通道 |
| `AnydocUnavailableException` | 429 / 503 | 限流/过载/停机（携带 Retry-After） | SDK 已按 `max(退避, Retry-After)` 自动重试 |
| `AnydocCircuitOpenException` | — | 熔断冷却期 | 快速失败，转兜底队列 |
| `AnydocServiceException` | 5xx / 网络 | 瞬时故障 | 自动重试 → 熔断 → 兜底 |

```java
try {
    return client.convert(bytes, filename, opts);
} catch (UnsupportedDocumentException e) { /* 死信：未知格式/扫描件 */ }
catch (EncryptedDocumentException e)     { /* 隔离 */ }
catch (CorruptedDocumentException e)     { /* 死信 + 告警 */ }
catch (DocumentTooLargeException e)      { /* 拒绝 */ }
catch (AnydocTimeoutException e)         { /* 转 convertAsync 异步通道 */ }
catch (AnydocServiceException e)         { /* 已重试仍失败：兜底队列 */ }
```

### Spring Boot 自动装配

引入依赖后，通过 `rag.anydoc.*` 配置即自动注入 `AnydocClient` Bean 与 Actuator 健康指示器：

```yaml
rag:
  anydoc:
    base-url: ${ANYDOC_BASE_URL:http://localhost:8080}
    token: ${ANYDOC_SERVICE_TOKEN}
    connect-timeout: 2s
    request-timeout: 30s
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

SDK 需连接一个运行中的 anydoc-service。该服务**不开源**，以官方镜像方式发布，
部署只需拉取/导入镜像并配置环境变量。

### 服务概览（v2.0）

| 项 | 说明                                                                                                                                                    |
|---|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| 技术栈 | Python 3.12 + FastAPI + firecrawl-anydoc 0.1.8（Rust 内核）+ pypdfium2（渲染）                                                                                |
| 能力边界 | 格式转换 + 资产提取 + 页面渲染 + 异步任务；**不含 OCR / pdf-inspector**（v2.0 移除）                                                                                         |
| 端口 | 8080                                                                                                                                                  |
| API | `POST /v1/convert`（同步转换）· `POST /v1/jobs` / `GET /v1/jobs/{id}`（异步任务）· `POST /v1/pdf/render`（页面位图渲染）· `/healthz` · `/readyz` · `/metrics`（Prometheus） |
| 认证 | Bearer Token（`ANYDOC_SERVICE_TOKEN`，与 SDK `token` 一致）                                                                                                 |
| 版本配套 | anydoc-sdk ≥ 2.8.0 ↔ anydoc-service ≥ 2.8.0                                                                                                           |

### 获取镜像

```bash
# 方式 A：从镜像仓库拉取（地址以实际发布为准）
docker pull jubb/anydoc-service:2.8.0

# 方式 B：离线导入（内网环境：由服务方提供镜像 tar 包）
docker load -i anydoc-service-2.8.0.tar
```

> v2.8 镜像不含 paddle 依赖，体积相比 OCR 版本显著减小，启动秒级。

### 最简运行（Docker Desktop）

```bash
docker run -d --name anydoc-service \
  -p 8080:8080 \
  -e ANYDOC_SERVICE_TOKEN=<强随机Token> \
  --restart unless-stopped \
  anydoc-service:2.8.0
```

验证：

```bash
curl http://localhost:8080/healthz
curl -H "Authorization: Bearer <Token>" -F "file=@test.docx" http://localhost:8080/v1/convert
```

### 关键配置项（服务端环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANYDOC_SERVICE_TOKEN` | `dev-token` | Bearer Token，**生产必须改为强随机值** |
| `ANYDOC_MAX_BYTES` | 50MB | 上传大小上限 |
| `ANYDOC_MAX_CONCURRENCY` / `ANYDOC_QUEUE_WAIT_SECONDS` | 8 / 10s | 并发上限 / 排队超时（503 背压） |
| `ANYDOC_CONVERT_TIMEOUT_SECONDS` | 30s | 同步转换默认超时（可被请求参数 `timeout` 覆盖） |
| `ANYDOC_REQUEST_TIMEOUT_MAX` | 1800s | 请求级动态超时的钳制上限 |
| `ANYDOC_ASYNC_WORKERS` / `ANYDOC_ASYNC_QUEUE_SIZE` | 2 / 16 | 异步任务线程数 / 队列上限 |
| `ANYDOC_ASYNC_JOB_TIMEOUT_SECONDS` | 600s | 异步任务默认执行超时（可被提交参数覆盖） |
| `ANYDOC_JOB_RETENTION_SECONDS` | 3600s | 终态任务保留时长（供轮询） |
| `ANYDOC_DRAIN_TIMEOUT_SECONDS` | 20s | 优雅停机 drain 窗口（编排层 grace period 需 ≥ 该值） |
| `ANYDOC_RENDER_MAX_PAGES` / `ANYDOC_RENDER_MAX_SCALE` | 20 / 4.0 | 渲染 API 页数 / 倍率上限 |
| `ANYDOC_CACHE_ENABLED` / `ANYDOC_RATE_LIMIT_RPM` | true / 0 | 转换缓存 / Token 级限流 |

完整部署（生产加固 compose / K8s：只读根文件系统、探针、HPA/PDB/NetworkPolicy、
优雅停机 grace period 对齐）见服务方随镜像提供的部署清单。

## 🧪 示例工程

以下两个示例工程演示 anydoc-sdk 在真实 RAG 管道中的完整用法（摄取、分块、
向量化、图片资产增强），可直接克隆改造：

| 示例工程 | GitHub 地址 | 演示内容 |
|---|---|---|
| **rag-ingestion-service**（RAG 摄取服务） | `https://github.com/zll-g/rag-ingestion-service-example` | 同步/异步转换接入、异常契约驱动管道路由（死信/兜底队列）、图片章节锚定分块 |
| **image-enrichment-worker**（图片增强服务） | `https://github.com/zll-g/image-enrichment-worker-example` | 图片资产 VLM 描述增强、pHash 去重、回调回填 Markdown |

> 两个示例工程均依赖本 SDK（Maven 坐标 `io.github.zll-g:anydoc-sdk`），
> 与 anydoc-service 组成完整的三服务参考架构。

## 📡 可观测与运维

- **指标**（服务端 `/metrics`，Prometheus 格式）：`anydoc_convert_total`、
  `anydoc_duration_seconds`、`anydoc_jobs_total`、`anydoc_pdf_render_pages`、
  `anydoc_cache_hits_total`、`anydoc_singleflight_shares_total` 等；
- **SDK 侧**：`ConversionListener` 钩子（onSuccess/onFailure，含耗时与异常类型）
  可对接 Micrometer；X-Request-ID 与服务端日志（`request_id` 字段）一一对账；
- **健康检查**：`/healthz`（存活）/ `/readyz`（就绪；停机期返回 503 摘流）；
  Spring Boot 自动装配含 `AnydocHealthIndicator`。

## 🤝 参与贡献

欢迎 Issue 与 Pull Request：

1. Fork 本仓库并创建特性分支：`git checkout -b feature/xxx`
2. 本地验证：`mvn clean install`（全量契约测试须通过）
3. 提交 PR 并描述动机与测试结果

行为准则：保持企业级品质 —— 类型化异常、向后兼容的 API 演进、测试先行。
详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 📄 许可证

[Apache License 2.0](LICENSE) © the anydoc-sdk authors
