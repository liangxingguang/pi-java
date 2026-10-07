package com.pijava.mcp.protocol;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pijava.mcp.protocol.content.ContentAnnotations;

/**
 * A resource family addressed by an RFC 6570 URI template, from
 * {@code resources/templates/list} ({@code types.ts:106-114}).
 *
 * @param uriTemplate URI template
 * @param name        name
 * @param title       optional title
 * @param description optional description
 * @param mimeType    optional MIME type
 * @param annotations optional annotations
 * @param meta        optional {@code _meta}
 */
public record ResourceTemplate(
        String uriTemplate,
        String name,
        @Nullable String title,
        @Nullable String description,
        @Nullable String mimeType,
        @Nullable ContentAnnotations annotations,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
