package com.pijava.mcp.protocol;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of {@code resources/list} ({@code types.ts:116-120}).
 *
 * @param resources  listed resources
 * @param nextCursor optional pagination cursor
 * @param meta       optional {@code _meta}
 */
public record ListResourcesResult(
        List<Resource> resources,
        @Nullable String nextCursor,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
