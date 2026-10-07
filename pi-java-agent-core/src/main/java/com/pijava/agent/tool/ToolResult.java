package com.pijava.agent.tool;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

/**
 * Tool execution result.
 *
 * <p>Tools throw on failure; {@code execute()} never returns an error result.
 * The harness catches exceptions and wraps them as error content for the LLM.</p>
 *
 * @param content           text/image content returned to the LLM
 * @param details           structured details for logs or UI rendering (nullable)
 * @param usage             usage from the tool execution itself (nullable)
 * @param terminate         hint that the agent should stop after the current batch
 * @param addedToolNames    names of tools dynamically registered by this result
 *                          (Phase 2c — MCP tools; reserved field, always empty in 2b)
 * @param isError           the call succeeded but its result is an error for the model, carrying
 *                          the content the tool produced (pi {@code AgentToolResult.isError});
 *                          a tool that throws instead gets a message-only error result
 * @param structuredContent the machine-facing payload of the result, which replaces the text for
 *                          programmatic callers (pi {@code AgentToolResult.structuredContent});
 *                          its consumers — codemode and the {@code after_tool} hook rewrite — are
 *                          not ported yet, so nothing reads it today
 */
public record ToolResult<TDetails>(
    List<ContentBlock> content,
    TDetails details,
    UsageInfo usage,
    boolean terminate,
    List<String> addedToolNames,
    boolean isError,
    Object structuredContent
) {
    /** Defensively copies {@code addedToolNames}. */
    public ToolResult {
        addedToolNames = List.copyOf(addedToolNames);
    }

    /**
     * A successful result without structured content — the shape every call site used before
     * {@code isError} and {@code structuredContent} existed.
     */
    public ToolResult(
        List<ContentBlock> content,
        TDetails details,
        UsageInfo usage,
        boolean terminate,
        List<String> addedToolNames
    ) {
        this(content, details, usage, terminate, addedToolNames, false, null);
    }

    /**
     * A call that succeeded but whose result is an error for the model, keeping the content the
     * tool produced (pi's MCP path: the server answered, with {@code isError}).
     *
     * @param content the content to show the model
     * @param details structured details for logs or UI rendering
     * @param <T> the details type
     * @return the error result
     */
    public static <T> ToolResult<T> isError(List<ContentBlock> content, T details) {
        return new ToolResult<>(content, details, null, false, List.of(), true, null);
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
