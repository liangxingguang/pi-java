package com.pijava.ai.stream;

import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lenient parser for streamed tool-call argument JSON, extracted from
 * {@link StreamPartialBuilder} to keep it within the file-length limit.
 * Malformed JSON is retained verbatim under {@code _raw} rather than dropped.
 */
final class ToolArgumentParser {

    private ToolArgumentParser() {}

    @SuppressWarnings("unchecked") // Jackson ObjectMapper.readValue with generic Map type
    static Map<String, Object> parse(String rawJson) {
        try {
            return (Map<String, Object>) (Map<?, ?>) lenient().readValue(rawJson, Map.class);
        } catch (Exception e) {
            return Map.of("_raw", rawJson);
        }
    }

    /** ObjectMapper tolerant of common model-output JSON quirks. */
    static ObjectMapper lenient() {
        return new ObjectMapper()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
            .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);
    }
}
