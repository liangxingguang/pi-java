package com.pijava.mcp.protocol;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of {@code tools/list} ({@code types.ts:87-91}).
 *
 * @param tools      listed tools
 * @param nextCursor optional pagination cursor
 * @param meta       optional {@code _meta}
 */
public record ListToolsResult(
        List<McpTool> tools,
        @Nullable String nextCursor,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
