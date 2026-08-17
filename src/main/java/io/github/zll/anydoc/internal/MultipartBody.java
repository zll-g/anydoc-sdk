package io.github.zll.anydoc.internal;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * multipart/form-data 请求体构造（单文件字段）。
 *
 * <p>使用 {@link HttpRequest.BodyPublishers#concat} 流式拼装，避免整份拷贝大文档。
 */
public final class MultipartBody {

    private final String boundary;
    private final HttpRequest.BodyPublisher publisher;

    private MultipartBody(String boundary, HttpRequest.BodyPublisher publisher) {
        this.boundary = boundary;
        this.publisher = publisher;
    }

    public static MultipartBody of(String fieldName, String filename, byte[] content) {
        String boundary = "----anydoc-sdk-" + UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitize(filename);
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(head),
                HttpRequest.BodyPublishers.ofByteArray(content),
                HttpRequest.BodyPublishers.ofByteArray(tail));
        return new MultipartBody(boundary, publisher);
    }

    /** 防止文件名注入 multipart 头（CR/LF/双引号）。 */
    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document.bin";
        }
        return filename.replaceAll("[\\r\\n\"]", "_");
    }

    public String boundary() {
        return boundary;
    }

    public HttpRequest.BodyPublisher publisher() {
        return publisher;
    }
}
