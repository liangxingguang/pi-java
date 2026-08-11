package com.pijava.agent.tool;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.message.ContentBlock;

/**
 * Tool execution engine.
 * Phase 2b: sequential execution (one tool at a time).
 * Phase 3: parallel execution via StructuredTaskScope when appropriate.
 */
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolContext context;

    public ToolExecutor(ToolRegistry registry, ToolContext context) {
        this.registry = registry;
        this.context = context;
    }

    /**
     * Execute a batch of tool calls sequentially.
     * Each tool call produces a tool_result Entry.Message.
     */
    public List<Entry.Message> executeSequential(
            List<Action.ExecuteTool> toolActions,
            AbortSignal signal) {
        var results = new ArrayList<Entry.Message>();
        for (var action : toolActions) {
            List<ContentBlock> resultBlocks;
            boolean isError = false;

            try {
                var result = registry.execute(
                    action.toolName(), action.toolCallId(), action.arguments(),
                    signal, null, context);
                resultBlocks = result.content();
            } catch (Exception e) {
                resultBlocks = List.of(new ContentBlock.TextContent(
                    "Error: " + e.getMessage()));
                isError = true;
            }

            var entry = new Entry.Message(
                Entry.newHeader(-1, ""),  // seq + parentId set by harness
                "tool",
                List.of(new ContentBlock.ToolResultContent(
                    action.toolCallId(), action.toolName(),
                    resultBlocks, isError))
            );
            results.add(entry);
        }
        return results;
    }
}
