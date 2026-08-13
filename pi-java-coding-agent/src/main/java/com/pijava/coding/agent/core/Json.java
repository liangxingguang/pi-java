package com.pijava.coding.agent.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;

/**
 * Shared Jackson {@link ObjectMapper} for settings and CLI JSON handling.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.PUBLIC_ONLY)
        .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
        .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
        .setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE);

    private Json() {}

    /** The shared, thread-safe mapper. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
