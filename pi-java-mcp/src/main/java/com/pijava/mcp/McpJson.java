package com.pijava.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson binding for the MCP wire format (snake_case; unknown {@code _meta}
 * and extension fields pass through).
 *
 * <p>Null components are omitted ({@link JsonInclude.Include#NON_NULL}), so an
 * absent optional field is wire-absent rather than {@code null}.</p>
 */
public final class McpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private McpJson() {
    }

    /** The shared MCP mapper. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
