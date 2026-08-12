package com.pijava.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;

/**
 * Tool execution engine.
 *
 * <p>Phase 2b: sequential execution (one tool at a time). Phase 3: parallel
 * execution via {@code StructuredTaskScope} when appropriate.</p>
 *
 * <p>The harness's per-tool hook flow (Phase 2c) needs the raw
 * {@link ToolResult} before wrapping, so it uses {@link #executeRaw} rather
 * than the batch methods.</p>
 */
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolContext context;

    public ToolExecutor(ToolRegistry registry, ToolContext context) {
        this.registry = registry;
        this.context = context;
    }

    /**
     * Execute a batch of tool calls sequentially, wrapping each result into
     * a {@code tool} {@link Entry.Message}. Errors are caught and encoded as
     * error content blocks.
     */
    public List<Entry.Message> executeSequential(
            List<Action.ExecuteTool> toolActions,
            AbortSignal signal) {
        var results = new ArrayList<Entry.Message>();
        for (var action : toolActions) {
            results.add(executeOne(action, signal));
        }
        return results;
    }

    /** Execute a batch of tool calls in parallel (Phase 3). */
    public List<Entry.Message> executeParallel(
            List<Action.ExecuteTool> toolActions,
            AbortSignal signal) {
        throw new UnsupportedOperationException("Parallel tool execution is Phase 3");
    }

    /**
     * Execute a single tool call and return the raw result, without wrapping.
     * Used by the harness's per-tool hook flow (before_tool/after_tool).
     */
    public ToolResult<?> executeRaw(String toolName, String toolCallId,
                                    Map<String, Object> args, AbortSignal signal) throws Exception {
        return registry.execute(toolName, toolCallId, args, signal, null, context);
    }

    private Entry.Message executeOne(Action.ExecuteTool action, AbortSignal signal) {
        List<ContentBlock> resultBlocks;
        boolean isError = false;
        try {
            resultBlocks = registry.execute(
                    action.toolName(), action.toolCallId(), action.arguments(),
                    signal, null, context).content();
        } catch (Exception e) {
            resultBlocks = List.of(new ContentBlock.TextContent("Error: " + e.getMessage()));
            isError = true;
        }
        return new Entry.Message(
            Entry.newHeader(-1, ""),  // seq + parentId set by the harness
            "tool",
            List.of(new ContentBlock.ToolResultContent(
                action.toolCallId(), action.toolName(), resultBlocks, isError)));
    }
}
