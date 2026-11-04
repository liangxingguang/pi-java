package com.pijava.mcp.protocol.content;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result of {@code tools/call} ({@code content.ts:65-70}).
 *
 * @param content           content blocks
 * @param structuredContent any JSON value, including arrays and {@code null}
 *                          (MCP 2026-07-28; earlier revisions allowed only objects).
 *                          Absent is Java {@code null}, an explicit JSON {@code null} is
 *                          {@link com.fasterxml.jackson.databind.node.NullNode}.
 * @param error             whether the tool reported an error
 * @param meta              optional {@code _meta}
 */
public record CallToolResult(
        @Nullable List<McpContentBlock> content,
        @Nullable JsonNode structuredContent,
        @JsonProperty("isError") @Nullable Boolean error,
        @JsonProperty("_meta") @Nullable Map<String, Object> meta) {
}
