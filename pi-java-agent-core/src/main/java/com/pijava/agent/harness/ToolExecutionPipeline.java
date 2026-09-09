package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.ToolCallContext;
import com.pijava.agent.hook.ToolResultContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.ReplayKind;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tool execution pipeline: before_tool hooks → raw execution (parallel for
 * batches) → after_tool hooks → transcript entry (Phase 3 design §11.4).
 *
 * <p>Extracted from {@link ActionExecutor} so both files stay under the
 * 500-line limit. Hooks fire sequentially to keep ordering deterministic;
 * raw execution uses a virtual-thread executor (StructuredTaskScope is a
 * preview API in JDK 25).</p>
 *
 * <p>Each call is wrapped in a {@code tool.execute} telemetry span spanning
 * before_tool → raw → after_tool (observability design §5.3), and every call
 * writes {@link LaneRecord.ToolStarted}/{@link LaneRecord.ToolFinished} audit
 * records so the transcript entry's id lands in
 * {@code ToolStarted.resultEntryId} (design §6.1).</p>
 */
final class ToolExecutionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutionPipeline.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        int batchSize = calls.size();

        // Stage 1: open a per-call span + fire before_tool hooks (in order).
        var decisions = new ArrayList<BeforeToolDecision>(batchSize);
        var spans = new ArrayList<TelemetrySpan>(batchSize);
        var starts = new ArrayList<Long>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            var et = calls.get(i);
            starts.add(System.nanoTime());
            spans.add(openToolSpan(lane, et, i, batchSize));
            decisions.add(beforeToolDecision(laneName, et));
        }

        // Stage 2: raw execution — parallel for batches, sequential otherwise.
        List<RawToolResult> rawResults;
        if (batchSize > 1) {
            rawResults = runRawBatch(lane, decisions);
        } else {
            rawResults = new ArrayList<>();
            for (var decision : decisions) {
                rawResults.add(decision.allowed()
                    ? runRawSafely(lane, decision) : RawToolResult.denied());
            }
        }

        // Stage 3: after_tool hooks + close spans + audit records (in order).
        var outcomes = new ArrayList<ToolOutcome>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            var et = calls.get(i);
            var decision = decisions.get(i);
            var span = spans.get(i);
            long startNanos = starts.get(i);
            if (!decision.allowed()) {
                var outcome = ToolOutcome.denied(et);
                closeToolSpan(lane, span, et, decision, i, batchSize, outcome, startNanos);
                outcomes.add(outcome);
                continue;
            }
            var raw = rawResults.get(i);
            var result = raw.result();
            var afterResult = ctx.hookSystem().fireAfterTool(laneName,
                new ToolResultContext(laneName, et.toolCallId(), et.toolName(), result));
            if (afterResult != null) {
                result = afterResult;
            }
            var outcome = new ToolOutcome(
                result.content(), raw.isError(), result.terminate());
            closeToolSpan(lane, span, et, decision, i, batchSize, outcome, startNanos);
            outcomes.add(outcome);
        }
        return outcomes;
    }

    /**
     * Append a tool result entry to the lane transcript and return its id so
     * the {@link LaneRecord.ToolStarted}/{@link LaneRecord.ToolFinished} audit
     * records can link it as {@code resultEntryId} (design §6.1).
     */
    private String appendEntry(LaneState lane, Action.ExecuteTool call, ToolOutcome outcome) {
        var toolEntry = new Entry.Message(
            UUID.randomUUID().toString(), 0, null, null,
            new Message.ToolResultMessage(
                call.toolCallId(), call.toolName(), outcome.blocks(), outcome.isError()), null);
        lane.transcript.add(toolEntry);
        lane.pendingWrites.add(toolEntry);
        return toolEntry.id();
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

    // ═══════════════════════════════════════════════════════════
    // tool.execute telemetry span + audit records
    // ═══════════════════════════════════════════════════════════

    /**
     * Open a per-call {@code tool.execute} span nested under the open
     * {@code harness.run} span (falling back to the root context).
     */
    private TelemetrySpan openToolSpan(
            LaneState lane, Action.ExecuteTool et, int index, int batchSize) {
        var parent = lane.runSpan != null ? lane.runSpan : ctx.telemetry();
        return parent.openSpan(new SpanOptions("tool.execute", Map.of(
            "toolCallId", et.toolCallId(),
            "toolName", et.toolName(),
            "toolIndex", index,
            "batchSize", batchSize)));
    }

    /** Close the span, write audit records, metrics and the debug log line. */
    private void closeToolSpan(
            LaneState lane, TelemetrySpan span, Action.ExecuteTool et,
            BeforeToolDecision decision, int index, int batchSize,
            ToolOutcome outcome, long startNanos) {
        boolean allowed = decision.allowed();
        boolean isError = outcome.isError();
        boolean terminate = outcome.terminate();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        span.addAttribute("allowed", allowed);
        span.addAttribute("isError", isError);
        span.addAttribute("terminate", terminate);
        span.addAttribute("argsChars", safeArgsChars(decision.args()));

        String resultEntryId = appendEntry(lane, et, outcome);
        lane.records.add(new LaneRecord.ToolStarted(
            UUID.randomUUID().toString(), 0, lane.laneName, null, lane.runId,
            "", index, et.toolCallId(), et.toolName(),
            decision.args(), resultEntryId, ReplayKind.NEVER));
        lane.records.add(new LaneRecord.ToolFinished(
            UUID.randomUUID().toString(), 0, lane.laneName, null, lane.runId,
            et.toolCallId(), et.toolName(), isError, terminate,
            resultEntryId, durationMs));

        ctx.telemetry().incrementCounter("tool.executions", 1);
        if (isError) {
            ctx.telemetry().incrementCounter("tool.errors", 1);
        }
        ctx.telemetry().recordTiming("tool.execute.duration", durationMs);

        LOG.debug("[agent] tool {} lane={} name={} durationMs={} isError={}",
            allowed ? "done" : "denied", lane.laneName, et.toolName(), durationMs, isError);

        span.close();
    }

    /** Character count of the effective args for observability (never the args themselves). */
    private static int safeArgsChars(Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsBytes(args).length;
        } catch (Exception e) {
            return args.toString().length();
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
