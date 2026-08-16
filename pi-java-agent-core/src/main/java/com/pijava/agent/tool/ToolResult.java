package com.pijava.agent.tool;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

/**
 * Tool execution result.
 *
 * <p>Tools throw on failure; {@code execute()} never returns an error result.
 * The harness catches exceptions and wraps them as error content for the LLM.</p>
 *
 * @param content        text/image content returned to the LLM
 * @param details        structured details for logs or UI rendering (nullable)
 * @param usage          usage from the tool execution itself (nullable)
 * @param terminate      hint that the agent should stop after the current batch
 * @param addedToolNames names of tools dynamically registered by this result
 *                       (Phase 2c — MCP tools; reserved field, always empty in 2b)
 */
public record ToolResult<TDetails>(
    List<ContentBlock> content,
    TDetails details,
    UsageInfo usage,
    boolean terminate,
    List<String> addedToolNames
) {
    /** Defensively copies {@code addedToolNames}. */
    public ToolResult {
        addedToolNames = List.copyOf(addedToolNames);
    }

    /** Create a successful text-only result. */
    public static <T> ToolResult<T> success(String text) {
        return new ToolResult<>(
            List.of(new ContentBlock.TextContent(text)),
            null, null, false, List.of());
    }

    /** Create a successful result with details. */
    public static <T> ToolResult<T> success(String text, T details) {
        return new ToolResult<>(
            List.of(new ContentBlock.TextContent(text)),
            details, null, false, List.of());
    }

    /** Token usage info (aligned with pi). */
    public record UsageInfo(long inputTokens, long outputTokens) {}
}
