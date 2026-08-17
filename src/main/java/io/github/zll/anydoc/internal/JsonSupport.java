package io.github.zll.anydoc.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 共享 Jackson 配置（线程安全）。 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonSupport() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
