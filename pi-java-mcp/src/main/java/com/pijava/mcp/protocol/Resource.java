package com.pijava.mcp.protocol;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pijava.mcp.protocol.content.ContentAnnotations;

/**
 * A resource a server lists in {@code resources/list} ({@code types.ts:94-103}).
 *
 * @param uri         resource URI
 * @param name        name
 * @param title       optional title
 * @param description optional description
 * @param mimeType    optional MIME type
 * @param size        optional size in bytes
 * @param annotations optional annotations
 * @param meta        optional {@code _meta}
 */
public record Resource(
        String uri,
        String name,
        @Nullable String title,
        @Nullable String description,
        @Nullable String mimeType,
        @Nullable Double size,
        @Nullable ContentAnnotations annotations,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
