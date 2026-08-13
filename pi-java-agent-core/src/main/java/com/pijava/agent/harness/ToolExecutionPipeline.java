package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.hook.ToolCallContext;
import com.pijava.agent.hook.ToolResultContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;

/**
 * Per-tool execution pipeline: before_tool hooks → raw execution (parallel for
 * batches) → after_tool hooks → transcript entry (Phase 3 design §11.4).
 *
 * <p>Extracted from {@link ActionExecutor} so both files stay under the
 * 500-line limit. Hooks fire sequentially to keep ordering deterministic;
 * raw execution uses a virtual-thread executor (StructuredTaskScope is a
 * preview API in JDK 26).</p>
 */
final class ToolExecutionPipeline {

    private final ExecutionContext ctx;

    ToolExecutionPipeline(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Run the per-tool pipeline for a list of calls. Raw execution runs in
     * parallel when there are multiple calls.
     */
    List<ToolOutcome> executeStages(
            String laneName, LaneState lane, List<Action.ExecuteTool> calls) {
        var decisions = new ArrayList<BeforeToolDecision>();
        for (var et : calls) {
            decisions.add(beforeToolDecision(laneName, et));
        }

        List<RawToolResult> rawResults;
        if (calls.size() > 1) {
            rawResults = runRawBatch(lane, decisions);
        } else {
            rawResults = new ArrayList<>();
            for (var decision : decisions) {
                rawResults.add(decision.allowed()
                    ? runRawSafely(lane, decision) : RawToolResult.denied());
            }
        }

        var outcomes = new ArrayList<ToolOutcome>();
        for (int i = 0; i < calls.size(); i++) {
            var et = calls.get(i);
            var decision = decisions.get(i);
            if (!decision.allowed()) {
                outcomes.add(ToolOutcome.denied(et));
                continue;
            }
            var raw = rawResults.get(i);
            var result = raw.result();
            var afterResult = ctx.hookSystem().fireAfterTool(laneName,
                new ToolResultContext(laneName, et.toolCallId(), et.toolName(), result));
            if (afterResult != null) {
                result = afterResult;
            }
            outcomes.add(new ToolOutcome(
                result.content(), raw.isError(), result.terminate()));
        }
        return outcomes;
    }

    /** Append a tool result entry to the lane transcript. */
    void appendEntry(LaneState lane, Action.ExecuteTool call, ToolOutcome outcome) {
        var parentId = lane.lastEntry() != null
            ? lane.lastEntry().header().id() : "";
        var toolEntry = new Entry.Message(
            Entry.newHeader(lane.nextSeq(), parentId),
            "tool",
            List.of(new ContentBlock.ToolResultContent(
                call.toolCallId(), call.toolName(), outcome.blocks(), outcome.isError())));
        lane.transcript.add(toolEntry);
        lane.pendingWrites.add(new ProvisionedEntry(toolEntry));
    }

    /** Fire {@code before_tool} hooks and compute the effective arguments. */
    private BeforeToolDecision beforeToolDecision(String laneName, Action.ExecuteTool et) {
        var beforeResult = ctx.hookSystem().fireBeforeTool(laneName,
            new ToolCallContext(laneName, et.toolCallId(), et.toolName(), et.arguments()));
        if (beforeResult != null && !beforeResult.allowed()) {
            return BeforeToolDecision.deny(et);
        }
        var args = (beforeResult != null && beforeResult.arguments() != null)
            ? beforeResult.arguments() : et.arguments();
        return BeforeToolDecision.allow(et, args);
    }

    /** Execute the raw tool calls of one turn in parallel. */
    private List<RawToolResult> runRawBatch(
            LaneState lane, List<BeforeToolDecision> decisions) {
        var results = new ArrayList<RawToolResult>(
            Collections.nCopies(decisions.size(), null));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<RawToolResult>>(decisions.size());
            for (var decision : decisions) {
                if (!decision.allowed()) {
                    futures.add(null);
                    continue;
                }
                futures.add(executor.submit(() -> runRawSafely(lane, decision)));
            }
            for (int i = 0; i < decisions.size(); i++) {
                var future = futures.get(i);
                results.set(i, future != null
                    ? future.get() : RawToolResult.denied());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fillBatchErrors(results, decisions, "Tool batch interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            fillBatchErrors(results, decisions,
                "Tool batch failed: " + e.getCause().getMessage());
        }
        return results;
    }

    private static void fillBatchErrors(
            List<RawToolResult> results,
            List<BeforeToolDecision> decisions,
            String message) {
        for (int i = 0; i < decisions.size(); i++) {
            if (results.get(i) == null) {
                results.set(i, new RawToolResult(new ToolResult<>(
                    List.of(new ContentBlock.TextContent(message)),
                    null, null, true, List.of()), true));
            }
        }
    }

    /** Execute a single raw tool call, encoding failures as error results. */
    private RawToolResult runRawSafely(LaneState lane, BeforeToolDecision decision) {
        try {
            var result = ctx.toolExecutor().executeRaw(
                decision.call().toolName(), decision.call().toolCallId(),
                decision.args(), lane.abortSignal);
            return new RawToolResult(result, false);
        } catch (SecurityException e) {
            return new RawToolResult(new ToolResult<>(
                List.of(new ContentBlock.TextContent(
                    "Tool call not approved: " + e.getMessage())),
                null, null, false, List.of()), true);
        } catch (IllegalArgumentException e) {
            return new RawToolResult(new ToolResult<>(
                List.of(new ContentBlock.TextContent("Tool error: " + e.getMessage())),
                null, null, false, List.of()), true);
        } catch (Exception e) {
            return new RawToolResult(new ToolResult<>(
                List.of(new ContentBlock.TextContent("Error: " + e.getMessage())),
                null, null, false, List.of()), true);
        }
    }

    /** Result of the before_tool stage. */
    record BeforeToolDecision(
        Action.ExecuteTool call,
        boolean allowed,
        Map<String, Object> args
    ) {
        static BeforeToolDecision allow(
                Action.ExecuteTool call, Map<String, Object> args) {
            return new BeforeToolDecision(call, true, args);
        }

        static BeforeToolDecision deny(Action.ExecuteTool call) {
            return new BeforeToolDecision(call, false, Map.of());
        }
    }

    /** Raw tool execution result plus whether the execution failed. */
    record RawToolResult(ToolResult<?> result, boolean isError) {
        static RawToolResult denied() {
            return new RawToolResult(new ToolResult<>(
                List.of(new ContentBlock.TextContent("Tool call denied by hook")),
                null, null, false, List.of()), true);
        }
    }

    /** Per-tool outcome used to build the transcript entry. */
    record ToolOutcome(
        List<ContentBlock> blocks,
        boolean isError,
        boolean terminate
    ) {
        static ToolOutcome denied(Action.ExecuteTool et) {
            return new ToolOutcome(
                List.of(new ContentBlock.ToolResultContent(
                    et.toolCallId(), et.toolName(),
                    List.of(new ContentBlock.TextContent("Tool call denied by hook")), true)),
                true, false);
        }
    }
}
