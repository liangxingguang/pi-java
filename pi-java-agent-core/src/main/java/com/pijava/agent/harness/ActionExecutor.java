package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.context.OverflowDetector;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.CompactionContext;
import com.pijava.agent.hook.PrepareNextTurnContext;
import com.pijava.agent.hook.RequestContext;
import com.pijava.agent.hook.ResponseContext;
import com.pijava.agent.hook.RunContext;
import com.pijava.agent.hook.RunEndContext;
import com.pijava.agent.hook.ShouldStopAfterTurnContext;
import com.pijava.agent.prompt.SystemPromptBuilder;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.ai.AbortSignal;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.ai.Usage;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes individual {@link Action} subclasses for {@link AgentHarness}.
 *
 * <p>Package-private — only {@code AgentHarness} creates and calls this.
 * Extracted from {@code AgentHarness} in Phase 2c to keep file sizes
 * under the 500-line limit.</p>
 */
final class ActionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ActionExecutor.class);

    private final ExecutionContext ctx;
    private final ToolExecutionPipeline toolPipeline;

    ActionExecutor(ExecutionContext ctx) {
        this.ctx = ctx;
        this.toolPipeline = new ToolExecutionPipeline(ctx);
    }

    // ═══════════════════════════════════════════════════════════
    // Run initiation
    // ═══════════════════════════════════════════════════════════

    /** Start a run from drained queue items (merged into one user message). */
    private Action runQueued(String laneName, List<LaneInfo.QueuedItem> items) {
        var prompt = items.stream().map(LaneInfo.QueuedItem::prompt)
            .collect(java.util.stream.Collectors.joining("\n\n"));
        var images = items.stream().flatMap(i -> i.images().stream()).toList();
        return run(laneName, prompt, images);
    }

    /** Initiate a new run on the specified lane. */
    Action run(String laneName, String prompt) {
        return run(laneName, prompt, List.of());
    }

    /** Initiate a new run on the specified lane with attached images. */
    Action run(String laneName, String prompt, List<PromptImage> images) {
        var lane = ctx.requireLane(laneName);
        if (!(lane.phase instanceof RunPhase.Idle)) {
            throw new IllegalStateException("Cannot start run: lane " + laneName + " is not idle");
        }
        lane.runId = UUID.randomUUID().toString();
        lane.stepIndex = 0;
        lane.partial = null;
        lane.newestOwn = null;
        // pi alignment (agent-loop.ts): consecutive prompts append to the
        // existing transcript — only reset() clears context.
        lane.pendingWrites.clear();
        lane.records.clear();
        lane.pendingToolCalls.clear();
        lane.pendingTurnUpdate = null;
        lane.abortSignal = AbortSignal.create();
        lane.runStartNanos = System.nanoTime();
        lane.runSpan = openRunSpan(laneName, lane, prompt.length());

        var userMessage = buildUserMessage(prompt, images);
        var promptList = List.<Message>of(userMessage);
        ctx.hookSystem().fireBeforeRun(laneName,
            new RunContext(laneName, lane.runId, promptList));

        // Write user message entry
        var userEntry = new Entry.Message(
            UUID.randomUUID().toString(), 0, lane.lastEntry() != null ? lane.lastEntry().id() : null,
            null, userMessage, null);
        lane.transcript.add(userEntry);
        lane.pendingWrites.add(userEntry);

        // Write thinking level change if non-default
        if (ctx.thinkingLevel().get() instanceof ModelThinkingLevel.Enabled en) {
            var tlEntry = new Entry.ThinkingLevelChange(
                UUID.randomUUID().toString(), 0,
                lane.lastEntry() != null ? lane.lastEntry().id() : null, null,
                en.level().label());
            lane.transcript.add(tlEntry);
            lane.pendingWrites.add(tlEntry);
        }

        lane.phase = RunPhase.ASSISTANT;
        // pi alignment: the operation id IS the runId (state.openOperationsByLane
        // pairs operation_finished.runId with operation_started.id) — a separate
        // UUID would never match and would leak an open operation on the lane.
        lane.records.add(new LaneRecord.OperationStarted(
            lane.runId, 0, laneName, null, null,
            new LaneRecord.OperationStarted.Run(promptList, List.of(), null, null)));
        ctx.incrementTurn();
        ctx.publishState(laneName);
        return peekAction(laneName);
    }

    /** Clear lane transcript, queues, and run state (pi Agent.reset alignment). */
    void reset(String laneName) {
        var lane = ctx.requireLane(laneName);
        if (!(lane.phase instanceof RunPhase.Idle)) {
            throw new IllegalStateException(
                "Cannot reset: lane " + laneName + " is running");
        }
        synchronized (lane) {
            lane.transcript.clear();
            lane.pendingWrites.clear();
            lane.records.clear();
            lane.pendingToolCalls.clear();
            lane.partial = null;
            lane.newestOwn = null;
            lane.runId = null;
            lane.stepIndex = 0;
            lane.pendingTurnUpdate = null;
            lane.runSpan = null;
            lane.runStartNanos = 0;
            lane.steerQueue.clear();
            lane.followUpQueue.clear();
            lane.nextRunQueue.clear();
        }
    }

    /**
     * Continue a run from the current transcript tail (pi agentLoopContinue
     * alignment): no new user entry, straight into the assistant stream.
     */
    Action runContinue(String laneName) {
        var lane = ctx.requireLane(laneName);
        if (!(lane.phase instanceof RunPhase.Idle)) {
            throw new IllegalStateException(
                "Cannot continue: lane " + laneName + " is not idle");
        }
        if (lane.transcript.isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }
        var last = lane.lastEntry();
        if (last instanceof Entry.Message m
                && m.message() instanceof Message.AssistantMessage) {
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }
        lane.runId = UUID.randomUUID().toString();
        lane.stepIndex = 0;
        lane.partial = null;
        lane.newestOwn = null;
        lane.records.clear();
        lane.pendingToolCalls.clear();
        lane.abortSignal = AbortSignal.create();
        lane.runStartNanos = System.nanoTime();
        lane.runSpan = openRunSpan(laneName, lane, 0);

        ctx.hookSystem().fireBeforeRun(laneName,
            new RunContext(laneName, lane.runId, List.of()));
        // pi alignment: operation id == runId (see run()).
        lane.records.add(new LaneRecord.OperationStarted(
            lane.runId, 0, laneName, null, null,
            new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null)));
        ctx.incrementTurn();
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);
    }

    /** Compact the specified lane's transcript. */
    void compact(String laneName, CompactionSettings settings) {        var lane = ctx.requireLane(laneName);
        if (lane.transcript.size() <= 1) {
            throw new NothingToCompactException(laneName);
        }
        applyCompaction(laneName, lane, settings, CompactionService.estimateTokens(lane.transcript));
        ctx.publishState(laneName);
    }

    /** Fire before_compaction, compute the compacted transcript, and replace it. */
    private void applyCompaction(String laneName, LaneState lane,
                                 CompactionSettings settings, int estimatedTokens) {
        ctx.telemetry().incrementCounter("compactions", 1);
        int entriesBefore = lane.transcript.size();
        var span = (lane.runSpan != null ? lane.runSpan : ctx.telemetry())
            .openSpan(new SpanOptions("compaction.apply",
                java.util.Map.of("reason", "auto", "estimatedTokens", estimatedTokens,
                    "entriesBefore", entriesBefore)));
        try {
            var compactCtx = new CompactionContext(laneName,
                List.copyOf(lane.transcript), estimatedTokens);
            var plan = ctx.hookSystem().fireBeforeCompaction(laneName, compactCtx);
            List<Entry> compacted;
            if (plan != null && !plan.keepEntries().isEmpty()) {
                compacted = plan.keepEntries();
            } else {
                compacted = compactTranscript(lane, settings);
            }
            lane.transcript.clear();
            lane.transcript.addAll(compacted);
            span.addAttribute("entriesAfter", lane.transcript.size());
            LOG.info("[agent] compaction lane={} tokensBefore={} entries {}->{}",
                laneName, estimatedTokens, entriesBefore, lane.transcript.size());
        } finally {
            span.close();
        }
    }

    private List<Entry> compactTranscript(LaneState lane, CompactionSettings settings) {
        var result = CompactionService.compact(lane.transcript, settings, ctx.summaryGenerator());
        var retainedTail = keptMessagesFrom(lane.transcript, result.firstKeptEntryId());
        var compactionEntry = new Entry.Compaction(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            java.time.Instant.now(), result.summary(), result.firstKeptEntryId(),
            retainedTail, (int) result.tokensBefore(), result.details(), result.usage());
        var kept = new ArrayList<Entry>();
        String firstKept = result.firstKeptEntryId();
        boolean seen = false;
        for (var entry : lane.transcript) {
            if (seen) {
                kept.add(entry);
            } else if (entry.id().equals(firstKept)) {
                kept.add(entry);
                seen = true;
            }
        }
        kept.add(0, compactionEntry);
        return kept;
    }

    private static List<Message> keptMessagesFrom(List<Entry> transcript, String firstKeptId) {
        List<Message> kept = new ArrayList<>();
        boolean seen = false;
        for (var entry : transcript) {
            if (entry.id().equals(firstKeptId)) {
                seen = true;
            }
            if (seen && entry instanceof Entry.Message msg) {
                kept.add(msg.message());
            }
        }
        return kept;
    }

    // ═══════════════════════════════════════════════════════════
    // Manual drive
    // ═══════════════════════════════════════════════════════════

    /** Return the next pending action from the specified lane. */
    Action peekAction(String laneName) {
        var lane = ctx.requireLane(laneName);
        return switch (lane.phase) {
            case RunPhase.Idle i -> {
                // Start a new run when any queue has items (Phase 3). Steer is
                // polled first, then nextRun, then followUp (aligned with pi's
                // outer loop which polls steering before follow-up queues).
                var steer = ctx.queueManager().drainSteer(laneName);
                if (!steer.isEmpty()) {
                    yield runQueued(laneName, steer);
                }
                var nextRun = ctx.queueManager().drainNextRun(laneName);
                if (!nextRun.isEmpty()) {
                    yield runQueued(laneName, nextRun);
                }
                var followUps = ctx.queueManager().drainFollowUp(laneName);
                if (!followUps.isEmpty()) {
                    yield runQueued(laneName, followUps);
                }
                yield null;
            }
            case RunPhase.Assistant a -> {
                var pw = drainNextPendingWrite(lane);
                if (pw != null) yield pw;
                if (!lane.pendingToolCalls.isEmpty()) {
                    if (ctx.toolExecution().get() instanceof ToolExecution.Parallel
                            && lane.pendingToolCalls.size() > 1
                            && !hasSequentialTool(lane.pendingToolCalls)) {
                        var calls = List.copyOf(lane.pendingToolCalls);
                        lane.pendingToolCalls.clear();
                        yield new Action.ExecuteToolBatch(calls);
                    }
                    yield lane.pendingToolCalls.remove(0);
                }
                // Inject queued steering messages before the next LLM round (Phase 3).
                var steer = ctx.queueManager().drainSteer(laneName);
                if (!steer.isEmpty()) {
                    injectUserMessages(lane, steer);
                    yield peekAction(laneName);
                }                yield new Action.StreamAssistant("assistant", 0);
            }
            case RunPhase.Checkpoint c -> {
                var pw = drainNextPendingWrite(lane);
                if (pw != null) yield pw;
                yield new Action.TryFinishRun(HarnessUtils.determineOutcome(lane));
            }
        };
    }

    /** Execute a single action on the specified lane. */
    Action executeAction(String laneName, Action action) {
        var lane = ctx.requireLane(laneName);
        return switch (action) {
            case Action.StreamAssistant sa -> executeStreamAssistant(laneName, lane, sa);
            case Action.AppendEntry ae -> executeAppendEntry(lane, ae);
            case Action.TryFinishRun tfr -> executeTryFinishRun(laneName, lane, tfr);
            case Action.ExecuteTool et -> executeTool(laneName, lane, et);
            case Action.ExecuteToolBatch etb -> executeToolBatch(laneName, lane, etb);
        };
    }

    /** Append queued steering prompts as user entries (Phase 3). */
    private void injectUserMessages(LaneState lane, List<LaneInfo.QueuedItem> items) {
        var prompt = items.stream().map(LaneInfo.QueuedItem::prompt)
            .collect(java.util.stream.Collectors.joining("\n\n"));
        var images = items.stream().flatMap(i -> i.images().stream()).toList();
        var userEntry = new Entry.Message(
            UUID.randomUUID().toString(), 0,
            lane.lastEntry() != null ? lane.lastEntry().id() : null, null,
            buildUserMessage(prompt, images), null);
        lane.transcript.add(userEntry);
        lane.pendingWrites.add(userEntry);
    }

    /** Build a user message: text first, then images (pi agent.ts:402-406 order). */
    private static Message buildUserMessage(String prompt, List<PromptImage> images) {
        var content = new ArrayList<ContentBlock>();
        content.add(new ContentBlock.TextContent(prompt));
        if (images != null) {
            images.forEach(img -> content.add(img.toContentBlock()));
        }
        return new Message.UserMessage(content);
    }

    private Action drainNextPendingWrite(LaneState lane) {
        if (!lane.pendingWrites.isEmpty()) {
            var entry = lane.pendingWrites.get(0);
            return new Action.AppendEntry(entry.type(), entry.id());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    // StreamAssistant
    // ═══════════════════════════════════════════════════════════

    private Action executeStreamAssistant(String laneName, LaneState lane,
                                           Action.StreamAssistant sa) {
        if (lane.abortSignal != null && lane.abortSignal.isAborted()) {
            lane.phase = RunPhase.CHECKPOINT;
            lane.partial = AssistantMessage.empty().withStopReason("aborted");
            lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
            return peekAction(laneName);
        }

        // Auto-compaction: check token budget before building messages
        applyPendingTurnUpdate(laneName, lane);
        checkAutoCompact(laneName, lane);

        var messages = buildMessagesForLane(laneName, lane);
        var thinkingConfig = ctx.thinkingLevelMap().forLevel(ctx.thinkingLevel().get());

        // Fire before_request
        ctx.hookSystem().fireBeforeRequest(laneName,
            new RequestContext(laneName, lane.runId, messages));

        // Build tool definitions, respecting lane-level tool overrides
        var effectiveTools = lane.activeTools != null ? lane.activeTools : ctx.activeTools().get();
        var allToolDefs = ctx.toolRegistry() != null
            ? ctx.toolRegistry().toToolDefinitions() : List.<ToolDefinition>of();
        var activeNames = effectiveTools.stream().map(AgentTool::name)
            .collect(Collectors.toSet());
        var toolDefs = allToolDefs.stream()
            .filter(td -> activeNames.contains(td.name())).toList();
        var options = new StreamOptions(
            java.util.OptionalInt.empty(), java.util.OptionalDouble.empty(),
            thinkingConfig, toolDefs);

        int attemptIdx = lane.stepIndex++;
        long inputTokens = 0;
        long outputTokens = 0;
        Throwable streamError = null;
        long llmStart = System.nanoTime();
        var llmSpan = (lane.runSpan != null ? lane.runSpan : ctx.telemetry())
            .openSpan(new SpanOptions("llm.request",
                java.util.Map.of(
                    "attempt", attemptIdx,
                    "model", modelLabel(ctx.model().get()),
                    "messageCount", messages.size(),
                    "toolCount", toolDefs.size(),
                    "thinking", thinkingLabel(ctx.thinkingLevel().get()))));
        try {
            ctx.telemetry().pushCurrent(llmSpan);
            try {
                var iter = ctx.streamFn().stream(messages, ctx.model().get(), options);
                try {
                    while (iter.hasNext()) {
                        if (lane.abortSignal != null && lane.abortSignal.isAborted()) {
                            iter.close();
                            break;
                        }
                        var event = iter.next();
                        ctx.streamListener().get().accept(event);
                        if (event instanceof StreamEvent.UsageInfo ui
                                && ui.partial() != null && ui.partial().usage() != null) {
                            inputTokens = ui.partial().usage().inputTokens();
                            outputTokens = ui.partial().usage().outputTokens();
                        }
                        if (event.partial() != null) {
                            lane.partial = event.partial();
                        }
                        if (event instanceof StreamEvent.StreamDone) break;
                        if (event instanceof StreamEvent.StreamError) break;
                    }
                } finally {
                    iter.close();
                }
            } finally {
                ctx.telemetry().popCurrent(llmSpan);
            }
        } catch (Exception e) {
            streamError = e;
            lane.partial = AssistantMessage.empty().withStopReason("error");
        } finally {
            long durationMs = (System.nanoTime() - llmStart) / 1_000_000;
            String stop = lane.partial != null ? lane.partial.stopReason() : null;
            llmSpan.addAttribute("inputTokens", inputTokens);
            llmSpan.addAttribute("outputTokens", outputTokens);
            if (stop != null) {
                llmSpan.addAttribute("stopReason", stop);
            }
            if (streamError != null) {
                llmSpan.addAttribute("errorClass", streamError.getClass().getSimpleName());
            }
            llmSpan.close();
            ctx.telemetry().incrementCounter("llm.requests", 1);
            ctx.telemetry().recordTiming("llm.request.duration", durationMs);
            if (inputTokens > 0) {
                ctx.telemetry().incrementCounter("llm.tokens.input", inputTokens);
            }
            if (outputTokens > 0) {
                ctx.telemetry().incrementCounter("llm.tokens.output", outputTokens);
            }
            LOG.debug("[agent] llm done lane={} model={} msgCount={} toolCount={} "
                    + "in={} out={} durationMs={} stop={}",
                laneName, modelLabel(ctx.model().get()), messages.size(),
                toolDefs.size(), inputTokens, outputTokens, durationMs, stop);
            if (streamError != null) {
                LOG.warn("[agent] llm stream error lane={} runId={}",
                    laneName, lane.runId, streamError);
            }
        }

        // Context overflow detection: trigger compaction when the response
        // signals an overflow (error message, token count, or zero-output+length).
        String stopReason = lane.partial != null ? lane.partial.stopReason() : null;
        var usageInfo = new StreamEvent.UsageInfo(inputTokens, outputTokens, lane.partial);
        if (OverflowDetector.isOverflow(streamError, stopReason, usageInfo, ctx.maxInputTokens())) {
            var settings = ctx.compactionSettings().get();
            if (settings != null && lane.transcript.size() > 1) {
                applyCompaction(laneName, lane, settings,
                    CompactionService.estimateTokens(lane.transcript));
            }
        }

        // Fire after_response
        ctx.hookSystem().fireAfterResponse(laneName,
            new ResponseContext(laneName, lane.runId, lane.partial,
                new StreamEvent.UsageInfo(inputTokens, outputTokens, lane.partial)));

        String asstEntryId = null;
        if (lane.partial != null) {
            asstEntryId = UUID.randomUUID().toString();
            var asstEntry = new Entry.Message(
                asstEntryId, 0, lane.lastEntry() != null ? lane.lastEntry().id() : null, null,
                new Message.AssistantMessage(lane.partial.content()), null);
            lane.transcript.add(asstEntry);
            lane.pendingWrites.add(asstEntry);
        }

        lane.records.add(new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            StepKind.ASSISTANT, attemptIdx, asstEntryId == null ? "" : asstEntryId, null,
            modelLabel(ctx.model().get()), messages.size(), toolDefs.size(),
            thinkingLabel(ctx.thinkingLevel().get()),
            (System.nanoTime() - llmStart) / 1_000_000));
        if (inputTokens > 0 || outputTokens > 0) {
            lane.records.add(new LaneRecord.UsageRecord(
                UUID.randomUUID().toString(), 0, laneName, null,
                Usage.of(inputTokens, outputTokens), UsageCause.ASSISTANT,
                lane.runId, asstEntryId, null, attemptIdx, stopReason));
            ctx.addTokens(inputTokens + outputTokens);
        }

        lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
        lane.phase = RunPhase.CHECKPOINT;
        return peekAction(laneName);
    }

    // ── AppendEntry ─────────────────────────────────────────

    private Action executeAppendEntry(LaneState lane, Action.AppendEntry ae) {
        lane.pendingWrites.removeIf(entry -> entry.id().equals(ae.entryId()));
        return peekAction(lane.laneName);
    }

    // ── TryFinishRun ────────────────────────────────────────

    private Action executeTryFinishRun(String laneName, LaneState lane,
                                        Action.TryFinishRun tfr) {
        String status = tfr.outcome();
        // pi alignment: prepareNextTurn fires after turn_end, before the
        // shouldStop check (agent-loop.ts:232→248). Scoped to THIS run: the
        // update is applied by the next StreamAssistant and cleared at run end.
        if ("completed".equals(status) || "tool_use".equals(status)) {
            var upd = ctx.hookSystem().firePrepareNextTurn(laneName,
                new PrepareNextTurnContext(laneName, lane.runId, lane.partial, List.of()));
            if (upd != null) lane.pendingTurnUpdate = upd;
        }
        if ("tool_use".equals(status)) {
            List<Action.ExecuteTool> toolActions = HarnessUtils.extractToolCalls(lane.partial);
            if (!toolActions.isEmpty()) {
                lane.pendingToolCalls.addAll(toolActions);
                lane.phase = RunPhase.ASSISTANT;
                lane.records.add(new LaneRecord.OperationFinished(
                    UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                    OperationOutcome.COMPLETED, null, runDurationMs(lane)));
                return peekAction(laneName);
            }
            // tool_use stop reason but no tool calls → complete the run instead
            status = "completed";
        }

        // pi alignment: shouldStopAfterTurn — after a completed turn, hooks
        // may end the run before queued follow-ups would start the next one.
        if ("completed".equals(status)) {
            var stop = ctx.hookSystem().fireShouldStopAfterTurn(laneName,
                new ShouldStopAfterTurnContext(laneName, lane.runId, lane.partial, List.of()));
            if (stop) {
                lane.records.add(new LaneRecord.OperationFinished(
                    UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                    OperationOutcome.COMPLETED, null, runDurationMs(lane)));
                ctx.hookSystem().fireBeforeRunEnd(laneName,
                    new RunEndContext(laneName, lane.runId, status));
                closeRunSpan(lane, status);
                lane.phase = RunPhase.IDLE;
                lane.pendingTurnUpdate = null;
                return null;
            }
        }

        // Terminal outcome (completed / error): fire before_run_end and finish
        lane.records.add(new LaneRecord.OperationFinished(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            "error".equals(status) ? OperationOutcome.FAILED : OperationOutcome.COMPLETED,
            null, runDurationMs(lane)));
        ctx.hookSystem().fireBeforeRunEnd(laneName,
            new RunEndContext(laneName, lane.runId, status));
        closeRunSpan(lane, status);

        lane.phase = RunPhase.IDLE;
        lane.pendingTurnUpdate = null;
        // Start the next run from queued follow-up messages (Phase 3).
        // One-at-a-time leaves the rest queued; they are drained when each
        // subsequent run finishes.
        var followUps = ctx.queueManager().drainFollowUp(laneName);
        if (!followUps.isEmpty()) {
            return runQueued(laneName, followUps);
        }
        return null;
    }

    // ── ExecuteTool ─────────────────────────────────────────

    private Action executeTool(String laneName, LaneState lane, Action.ExecuteTool et) {
        // ToolExecutionPipeline appends the transcript entry and writes the
        // ToolStarted/ToolFinished audit records + tool.execute span.
        var outcome = toolPipeline.executeStages(laneName, lane, List.of(et)).getFirst();

        if (outcome.terminate() && lane.pendingToolCalls.isEmpty()) {
            lane.pendingWrites.clear();
            lane.records.add(new LaneRecord.OperationFinished(
                UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                OperationOutcome.COMPLETED, null, runDurationMs(lane)));
            closeRunSpan(lane, "completed");
            lane.phase = RunPhase.IDLE;
            return null;
        }
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);
    }

    /** Execute a batch of tool calls in parallel (Phase 3, ToolExecution.Parallel). */
    private Action executeToolBatch(String laneName, LaneState lane,
                                     Action.ExecuteToolBatch batch) {
        // ToolExecutionPipeline appends transcript entries + writes the
        // ToolStarted/ToolFinished audit records + per-call tool.execute spans.
        var outcomes = toolPipeline.executeStages(laneName, lane, batch.calls());
        // pi alignment (agent-loop.ts shouldTerminateToolBatch): end the run only
        // when EVERY call in the batch asks for it. A single terminating call must
        // not silently drop the remaining calls' results.
        boolean allTerminate = !outcomes.isEmpty();
        for (var outcome : outcomes) {
            allTerminate &= outcome.terminate();
        }
        if (allTerminate) {
            lane.pendingWrites.clear();
            lane.records.add(new LaneRecord.OperationFinished(
                UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                OperationOutcome.COMPLETED, null, runDurationMs(lane)));
            closeRunSpan(lane, "completed");
            lane.phase = RunPhase.IDLE;
            return null;
        }
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Whether any pending call belongs to a tool that declared
     * {@link ExecutionMode#Sequential}.
     *
     * <p>pi alignment ({@code agent-loop.ts:419-424}, {@code hasSequentialToolCall}):
     * one sequential tool demotes the whole batch to sequential execution —
     * the batch is never split into a parallel part and a serial part.
     */
    private boolean hasSequentialTool(List<Action.ExecuteTool> calls) {
        if (ctx.toolRegistry() == null) {
            return false;
        }
        return calls.stream()
            .map(call -> ctx.toolRegistry().get(call.toolName()))
            .anyMatch(tool -> tool != null
                && tool.executionMode() instanceof ExecutionMode.Sequential);
    }

    /** Apply a pending prepare_next_turn update; write a change entry only when the value truly changed. */
    private void applyPendingTurnUpdate(String laneName, LaneState lane) {
        if (lane.pendingTurnUpdate == null) return;
        var upd = lane.pendingTurnUpdate;
        lane.pendingTurnUpdate = null;
        if (upd.model() != null) {
            var current = ctx.model().get();
            boolean changed = !current.modelName().equals(upd.model().modelName())
                || !current.provider().equals(upd.model().provider());
            ctx.turnConfigApplier().accept(upd.model(), null);
            if (changed) {
                var e = new Entry.ModelChange(UUID.randomUUID().toString(), lane.nextSeq(),
                    HarnessUtils.lastEntryId(lane), java.time.Instant.now(),
                    upd.model().provider(), upd.model().modelName());
                lane.transcript.add(e);
                lane.pendingWrites.add(e);
            }
        }
        if (upd.thinkingLevel() != null) {
            var cur = ctx.thinkingLevel().get();
            boolean changed;
            if ("off".equals(upd.thinkingLevel())) {
                changed = !(cur instanceof ModelThinkingLevel.Off);
            } else {
                changed = !(cur instanceof ModelThinkingLevel.Enabled en
                    && en.level().label().equals(upd.thinkingLevel()));
            }
            ctx.turnConfigApplier().accept(null, upd.thinkingLevel());
            if (changed) {
                var e = new Entry.ThinkingLevelChange(UUID.randomUUID().toString(), lane.nextSeq(),
                    HarnessUtils.lastEntryId(lane), java.time.Instant.now(), upd.thinkingLevel());
                lane.transcript.add(e);
                lane.pendingWrites.add(e);
            }
        }
    }

    void checkAutoCompact(String laneName, LaneState lane) {
        var settings = ctx.compactionSettings().get();
        if (settings == null) return;
        if (lane.transcript.size() <= 1) return;
        int estimatedTokens = CompactionService.estimateTokens(lane.transcript);
        if (settings.enabled() && estimatedTokens > ctx.maxInputTokens() - settings.reserveTokens()) {
            applyCompaction(laneName, lane, settings, estimatedTokens);
        }
    }

    List<Message> buildMessagesForLane(String laneName, LaneState lane) {
        var messages = new ArrayList<Message>();
        // Build system prompt with skills + tools
        var prompt = buildSystemPrompt(lane);
        if (prompt != null && !prompt.isEmpty()) {
            messages.add(new Message.SystemMessage(
                List.of(new ContentBlock.TextContent(prompt))));
        }
        // Compaction-aware context (pi buildContextEntries): compaction/branch
        // summaries become user messages instead of being dropped
        messages.addAll(ContextEntries.toMessages(
            ContextEntries.pathToLeaf(lane.transcript, HarnessUtils.lastEntryId(lane))));
        // Fire transform_context hook
        var transformed = ctx.hookSystem().fireTransformContext(laneName, messages);
        return new ArrayList<>(transformed);
    }

    private String buildSystemPrompt(LaneState lane) {
        var effectivePrompt = lane.systemPrompt != null
            ? lane.systemPrompt : ctx.systemPrompt().get();
        var effectiveTools = lane.activeTools != null
            ? lane.activeTools : ctx.activeTools().get();
        return new SystemPromptBuilder()
            .base(effectivePrompt)
            .tools(effectiveTools)
            .skills(ctx.skillManager().all())
            .build();
    }

    // ═══════════════════════════════════════════════════════════
    // Telemetry spans
    // ═══════════════════════════════════════════════════════════

    /** Open the {@code harness.run} span and record the run-start counter. */
    private TelemetrySpan openRunSpan(String laneName, LaneState lane, int promptChars) {
        ctx.telemetry().incrementCounter("harness.run", 1);
        var span = ctx.telemetry().openSpan(new SpanOptions("harness.run",
            java.util.Map.of("lane", laneName, "promptChars", promptChars)));
        LOG.info("[agent] run start lane={} runId={} promptChars={}",
            laneName, lane.runId, promptChars);
        return span;
    }

    /** Close the {@code harness.run} span with terminal attributes. */
    private void closeRunSpan(LaneState lane, String outcome) {
        var span = lane.runSpan;
        if (span == null) {
            return;
        }
        lane.runSpan = null;
        String stopReason = lane.partial != null ? lane.partial.stopReason() : null;
        if (stopReason != null) {
            span.addAttribute("stopReason", stopReason);
        }
        span.addAttribute("outcome", outcome);
        span.addAttribute("attemptCount", lane.stepIndex);
        LOG.info("[agent] run end lane={} runId={} outcome={} durationMs={}",
            lane.laneName, lane.runId, outcome,
            (System.nanoTime() - lane.runStartNanos) / 1_000_000);
        span.close();
    }

    /** Format the thinking mode as a label for attrs/records. */
    private static String thinkingLabel(ModelThinkingLevel level) {
        return level instanceof ModelThinkingLevel.Enabled en
            ? en.level().label() : "off";
    }

    /** Format the model as {@code provider/name}. */
    private static String modelLabel(ModelId<?> model) {
        return model.provider() + "/" + model.modelName();
    }

    /** Wall-clock milliseconds since the run started (for OperationFinished.durationMs). */
    private Long runDurationMs(LaneState lane) {
        return lane.runStartNanos == 0 ? null : (System.nanoTime() - lane.runStartNanos) / 1_000_000;
    }

}
