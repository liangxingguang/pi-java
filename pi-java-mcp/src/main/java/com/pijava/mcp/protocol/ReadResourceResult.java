package com.pijava.mcp.protocol;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pijava.mcp.protocol.content.ResourceContents;

/**
 * Result of {@code resources/read} ({@code types.ts:128-131}).
 *
 * @param contents resource contents
 * @param meta     optional {@code _meta}
 */
public record ReadResourceResult(
        List<ResourceContents> contents,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
