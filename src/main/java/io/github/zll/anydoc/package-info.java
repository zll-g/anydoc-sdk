/**
 * anydoc-service Java SDK（sdk-client）。
 *
 * <p>面向企业 RAG 摄取管道的文档转换客户端：调用 anydoc-service 将
 * Word / Excel / PowerPoint / PDF / ODF / RTF / EPUB / CSV 等 14 种格式
 * 统一转换为 GitHub Flavored Markdown。
 *
 * <h2>快速开始（纯 Java）</h2>
 * <pre>{@code
 * AnydocClient client = AnydocClient.builder()
 *         .baseUrl("http://anydoc-service:8080")
 *         .token(System.getenv("ANYDOC_SERVICE_TOKEN"))
 *         .build();                       // 线程安全单例，全局复用
 *
 * try {
 *     ConversionResult result = client.convert(bytes, "report.docx");
 *     String markdown = result.markdown();
 * } catch (UnsupportedDocumentException e) {
 *     // 415：未知格式/无文本层扫描件 → 死信（扫描件由上游 OCR 服务预处理）
 * } catch (EncryptedDocumentException e) {
 *     // 422：加密文档 → 隔离并通知所有者
 * } catch (CorruptedDocumentException e) {
 *     // 422：损坏/缺部件/触发安全上限 → 死信 + 告警
 * }
 * }</pre>
 *
 * <h2>Spring Boot</h2>
 * <p>引入本依赖后自动装配 {@code AnydocClient} Bean，配置前缀 {@code rag.anydoc.*}。
 *
 * <h2>线程安全</h2>
 * <p>{@link io.github.zll.anydoc.AnydocClient} 实现为不可变线程安全单例，
 * 内部共享 JDK {@code HttpClient} 连接池，禁止按请求创建客户端。
 */
package io.github.zll.anydoc;
