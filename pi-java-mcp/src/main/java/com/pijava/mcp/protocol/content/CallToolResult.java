package com.pijava.mcp.protocol.content;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of {@code tools/call} ({@code content.ts:65-70}).
 *
 * @param content           content blocks
 * @param structuredContent optional structured result
 * @param error             whether the tool reported an error
 * @param meta              optional {@code _meta}
 */
public record CallToolResult(
        @Nullable List<McpContentBlock> content,
        @Nullable Map<String, Object> structuredContent,
        @JsonProperty("isError") @Nullable Boolean error,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
