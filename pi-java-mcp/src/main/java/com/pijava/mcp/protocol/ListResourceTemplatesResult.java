package com.pijava.mcp.protocol;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of {@code resources/templates/list} ({@code types.ts:122-126}).
 *
 * @param resourceTemplates listed resource templates
 * @param nextCursor        optional pagination cursor
 * @param meta              optional {@code _meta}
 */
public record ListResourceTemplatesResult(
        List<ResourceTemplate> resourceTemplates,
        @Nullable String nextCursor,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
