package com.pijava.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

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

    /**
     * Execute a batch of tool calls in parallel (Phase 3).
     *
     * <p>Results are returned in declaration order. Failures are encoded as
     * error content blocks (same contract as {@link #executeSequential}).
     * The harness's per-tool hook flow does not use this method — it runs its
     * own staged batch through {@code ActionExecutor} so hooks stay ordered.</p>
     */
    public List<Entry.Message> executeParallel(
            List<Action.ExecuteTool> toolActions,
            AbortSignal signal) {
        var results = new ArrayList<Entry.Message>(
            Collections.nCopies(toolActions.size(), null));
        // Virtual-thread executor (StructuredTaskScope is preview in JDK 26).
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Entry.Message>>(toolActions.size());
            for (var action : toolActions) {
                futures.add(executor.submit(() -> executeOne(action, signal)));
            }
            for (int i = 0; i < toolActions.size(); i++) {
                results.set(i, futures.get(i).get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (int i = 0; i < toolActions.size(); i++) {
                if (results.get(i) == null) {
                    results.set(i, toolResultEntry(
                        toolActions.get(i).toolCallId(),
                        toolActions.get(i).toolName(),
                        List.of(new ContentBlock.TextContent("Tool batch interrupted")),
                        true));
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            for (int i = 0; i < toolActions.size(); i++) {
                if (results.get(i) == null) {
                    results.set(i, toolResultEntry(
                        toolActions.get(i).toolCallId(),
                        toolActions.get(i).toolName(),
                        List.of(new ContentBlock.TextContent(
                            "Tool batch failed: " + e.getCause().getMessage())),
                        true));
                }
            }
        }
        return results;
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
        return toolResultEntry(action.toolCallId(), action.toolName(), resultBlocks, isError);
    }

    private static Entry.Message toolResultEntry(String toolCallId, String toolName,
                                                 List<ContentBlock> blocks, boolean isError) {
        return new Entry.Message(
            java.util.UUID.randomUUID().toString(), 0, null, null,
            new Message.ToolResultMessage(toolCallId, toolName, blocks, isError), null);
    }
}