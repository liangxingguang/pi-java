package com.pijava.agent.harness;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.context.OverflowDetector;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.PrepareNextTurnContext;
import com.pijava.agent.hook.RequestContext;
import com.pijava.agent.hook.ResponseContext;
import com.pijava.agent.hook.RunContext;
import com.pijava.agent.hook.RunEndContext;
import com.pijava.agent.hook.ShouldStopAfterTurnContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.QueueKind;
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
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.SpanOptions;

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
    private final CompactionExecutor compactions;
    private final ContextAssembler contextAssembler;
    private final RunSpanFactory runSpans;

    ActionExecutor(ExecutionContext ctx) {
        this.ctx = ctx;
        this.toolPipeline = new ToolExecutionPipeline(ctx);
        this.compactions = new CompactionExecutor(ctx);
        this.contextAssembler = new ContextAssembler(ctx);
        this.runSpans = new RunSpanFactory(ctx);
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
        // existing transcript — only reset() clears context. The record log is
        // append-only (docs/21 D6): a run N enqueue is consumed by run N+1, so
        // the log must span runs for the fold to see the queue lifecycle.
        lane.pendingWrites.clear();
        lane.pendingToolCalls.clear();
        lane.pendingTurnUpdate = null;
        lane.abortSignal = AbortSignal.create();
        lane.runStartNanos = System.nanoTime();
        lane.runSpan = runSpans.openRunSpan(laneName, lane, prompt.length());

        var userMessage = HarnessUtils.buildUserMessage(prompt, images);
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
        lane.pendingToolCalls.clear();
        lane.abortSignal = AbortSignal.create();
        lane.runStartNanos = System.nanoTime();
        lane.runSpan = runSpans.openRunSpan(laneName, lane, 0);

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

    /** Compact the specified lane's transcript (delegated to {@link CompactionExecutor}). */
    void compact(String laneName, CompactionSettings settings) {
        compactions.compact(laneName, settings);
    }

    // ═══════════════════════════════════════════════════════════
    // Manual drive
    // ═══════════════════════════════════════════════════════════

    /** Return the next pending action from the specified lane. */
    Action peekAction(String laneName) {
        var lane = ctx.requireLane(laneName);
        var action = computeNextAction(laneName);
        assert LoopInvariants.hold(lane, action)
            : "invariant violated: " + LoopInvariants.diagnostics(lane, action);
        return action;
    }

    /**
     * Pure computation of the next action for a lane, driven by the run phase.
     * Kept separate from {@link #peekAction} so the loop invariants (docs/20
     * §4.1, L2-⑥) are asserted on every action the harness produces. Internal
     * recursion calls {@link #peekAction} so every level is checked.
     */
    private Action computeNextAction(String laneName) {
        var lane = ctx.requireLane(laneName);
        return switch (lane.phase) {
            case RunPhase.Idle i -> {
                // Start a new run when any queue has items (Phase 3). Steer is
                // polled first, then nextRun, then followUp (aligned with pi's
                // outer loop which polls steering before follow-up queues).
                // Each drain yields an explicit ConsumeQueueItem action (L3):
                // the queue is consumed whole per the QueueMode (one-at-a-time
                // or all), then the next run starts from the merged prompt.
                var steer = ctx.queueManager().drainSteer(laneName);
                if (!steer.isEmpty()) {
                    yield new Action.ConsumeQueueItem("steer", steer);
                }
                var nextRun = ctx.queueManager().drainNextRun(laneName);
                if (!nextRun.isEmpty()) {
                    yield new Action.ConsumeQueueItem("nextRun", nextRun);
                }
                var followUps = ctx.queueManager().drainFollowUp(laneName);
                if (!followUps.isEmpty()) {
                    yield new Action.ConsumeQueueItem("followUp", followUps);
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
                }
                // Abort guard (docs/20 §4.1 invariant 5): never issue another
                // LLM request once the lane is aborted. Transition to the
                // checkpoint so the run finalizes as "error" instead of
                // producing a StreamAssistant.
                if (lane.abortSignal != null && lane.abortSignal.isAborted()) {
                    lane.phase = RunPhase.CHECKPOINT;
                    lane.partial = AssistantMessage.empty().withStopReason("aborted");
                    lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
                    yield peekAction(laneName);
                }
                yield new Action.StreamAssistant("assistant", 0);
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
            case Action.ApplyPendingWrite apw -> executeApplyPendingWrite(lane, apw);
            case Action.TryFinishRun tfr -> executeTryFinishRun(laneName, lane, tfr);
            case Action.ExecuteTool et -> executeTool(laneName, lane, et);
            case Action.ExecuteToolBatch etb -> executeToolBatch(laneName, lane, etb);
            case Action.ConsumeQueueItem cqi -> executeConsumeQueueItem(laneName, lane, cqi);
            case Action.FinishOperation fo -> executeFinishOperation(laneName, lane, fo);
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
            HarnessUtils.buildUserMessage(prompt, images), null);
        lane.transcript.add(userEntry);
        lane.pendingWrites.add(userEntry);
        // Mid-run steer injection bypasses ConsumeQueueItem, so it must emit
        // the same record here — otherwise the fold would still see these
        // items as pending and a resume would inject them twice (docs/21 D10).
        emitQueueConsumed(lane, QueueKind.STEER, items);
    }

    /** Emit the record marking a drained batch as consumed (docs/21 D4). */
    private static void emitQueueConsumed(LaneState lane, QueueKind kind,
                                          List<LaneInfo.QueuedItem> items) {
        lane.records.add(new LaneRecord.QueueConsumed(
            UUID.randomUUID().toString(), 0, lane.laneName, null, lane.runId, kind,
            items.stream().map(HarnessUtils::provisionedQueueTarget).toList()));
    }

    private Action drainNextPendingWrite(LaneState lane) {
        if (!lane.pendingWrites.isEmpty()) {
            var entry = lane.pendingWrites.get(0);
            return new Action.ApplyPendingWrite(entry.type(), entry.id());
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
        contextAssembler.applyPendingTurnUpdate(laneName, lane);
        compactions.checkAutoCompact(laneName, lane);

        var messages = contextAssembler.buildMessagesForLane(laneName, lane);
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
                    "model", RunSpanFactory.modelLabel(ctx.model().get()),
                    "messageCount", messages.size(),
                    "toolCount", toolDefs.size(),
                    "thinking", RunSpanFactory.thinkingLabel(ctx.thinkingLevel().get()))));
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
                laneName, RunSpanFactory.modelLabel(ctx.model().get()), messages.size(),
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
                compactions.applyCompaction(laneName, lane, settings,
                    CompactionService.estimateTokens(lane.transcript), "overflow");
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
            RunSpanFactory.modelLabel(ctx.model().get()), messages.size(), toolDefs.size(),
            RunSpanFactory.thinkingLabel(ctx.thinkingLevel().get()),
            (System.nanoTime() - llmStart) / 1_000_000,
            stopReason));
        // Recorded unconditionally (docs/21): a zero-token turn (error /
        // abort before any usage was reported) is exactly the case whose
        // stopReason the fold needs, so gating on tokens>0 lost it.
        lane.records.add(new LaneRecord.UsageRecord(
            UUID.randomUUID().toString(), 0, laneName, null,
            Usage.of(inputTokens, outputTokens), UsageCause.ASSISTANT,
            lane.runId, asstEntryId, null, attemptIdx, stopReason));
        ctx.addTokens(inputTokens + outputTokens);

        lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
        lane.phase = RunPhase.CHECKPOINT;
        return peekAction(laneName);
    }

    // ── ApplyPendingWrite ───────────────────────────────────

    private Action executeApplyPendingWrite(LaneState lane, Action.ApplyPendingWrite apw) {
        lane.pendingWrites.removeIf(entry -> entry.id().equals(apw.entryId()));
        return peekAction(lane.laneName);
    }

    // ── ConsumeQueueItem ────────────────────────────────────

    private Action executeConsumeQueueItem(String laneName, LaneState lane,
                                            Action.ConsumeQueueItem cqi) {
        // Start the run first: it assigns lane.runId, which the consume record
        // carries as the run that consumed the items.
        var action = runQueued(laneName, cqi.items());
        emitQueueConsumed(lane, QueueKind.fromValue(cqi.queue()), cqi.items());
        return action;
    }

    // ── FinishOperation ─────────────────────────────────────

    private Action executeFinishOperation(String laneName, LaneState lane,
                                           Action.FinishOperation fo) {
        lane.records.add(new LaneRecord.OperationFinished(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            outcome(fo.outcome()), null, RunSpanFactory.runDurationMs(lane)));
        ctx.hookSystem().fireBeforeRunEnd(laneName,
            new RunEndContext(laneName, lane.runId, fo.outcome()));
        runSpans.closeRunSpan(lane, fo.outcome());

        // Terminated runs (denyAndTerminate / allTerminate) drop pending writes —
        // the tool result is not persisted when the operation ends early.
        lane.pendingWrites.clear();
        lane.phase = RunPhase.IDLE;
        lane.pendingTurnUpdate = null;
        if (fo.stop()) {
            // shouldStopAfterTurn hit / tool-terminated run: end the drive loop
            // here. Queued follow-ups stay in the queue for a future drive — a
            // stopping hook means "stop after this turn", not "start the next
            // queued run" (L3, preserves the pre-L3 return-null terminal paths).
            return null;
        }
        // Normal terminal: return to IDLE so the next peekAction produces a
        // ConsumeQueueItem action, chaining the follow-up within the same drive
        // loop (L3). No explicit drain here: follow-ups are enqueued externally
        // (AgentSession.followUp → QueueManager.followUp).
        return peekAction(laneName);
    }

    /** Map a finish_operation outcome string to its {@link OperationOutcome}. */
    private static OperationOutcome outcome(String value) {
        return switch (value) {
            case "aborted" -> OperationOutcome.ABORTED;
            case "failed" -> OperationOutcome.FAILED;
            case "declined" -> OperationOutcome.DECLINED;
            default -> OperationOutcome.COMPLETED;
        };
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
        // pi alignment (agent-loop.ts:211-214, 381): an output-token-limit
        // truncation must never execute possibly-truncated tool calls. Fail
        // them back into the transcript and let the model retry (inner loop
        // continues), rather than treating it as a terminal outcome.
        if ("length".equals(status)) {
            failTruncatedToolCalls(lane);
            lane.phase = RunPhase.ASSISTANT;
            return peekAction(laneName);
        }
        if ("tool_use".equals(status)) {
            List<Action.ExecuteTool> toolActions = HarnessUtils.extractToolCalls(lane.partial);
            if (!toolActions.isEmpty()) {
                lane.pendingToolCalls.addAll(toolActions);
                lane.phase = RunPhase.ASSISTANT;
                return peekAction(laneName);
            }
            // tool_use stop reason but no tool calls → complete the run instead
            status = "completed";
        }

        // pi alignment: shouldStopAfterTurn — after a completed turn, hooks
        // may end the run before queued follow-ups would start the next one.
        // A stopping hook is a terminal condition: finish the operation now
        // (the operation_finished record / before_run_end / idle transition
        // are handled by FinishOperation, L3).
        if ("completed".equals(status)) {
            var stop = ctx.hookSystem().fireShouldStopAfterTurn(laneName,
                new ShouldStopAfterTurnContext(laneName, lane.runId, lane.partial, List.of()));
            if (stop) {
                return new Action.FinishOperation(status, true);
            }
        }

        // Terminal outcome (completed / error): the operation ends. The
        // operation_finished record, before_run_end hook, run-span close and
        // idle transition are all handled by the FinishOperation action — this
        // method only selects the outcome and returns the action (L3).
        String outcome = "error".equals(status) ? "failed" : status;
        return new Action.FinishOperation(outcome);
    }

    // ── ExecuteTool ─────────────────────────────────────────

    private Action executeTool(String laneName, LaneState lane, Action.ExecuteTool et) {
        // ToolExecutionPipeline appends the transcript entry and writes the
        // ToolStarted/ToolFinished audit records + tool.execute span.
        var outcome = toolPipeline.executeStages(laneName, lane, List.of(et)).getFirst();

        if (outcome.terminate() && lane.pendingToolCalls.isEmpty()) {
            // A terminating tool outcome ends the drive here (pre-L3 return-null
            // path): no follow-up chain — the run was stopped mid-operation.
            return new Action.FinishOperation("completed", true);
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
            // Same as the single-call terminate path: end the drive without a
            // follow-up chain (pre-L3 return-null behavior).
            return new Action.FinishOperation("completed", true);
        }
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Fail every tool call in the latest assistant message back into the
     * transcript (pi {@code agent-loop.ts:211-214, 381}): the response hit the
     * output token limit, so the calls' arguments may be truncated mid-JSON
     * and must not be executed. The error result is appended as a tool message
     * so the model can re-issue the calls with complete arguments.
     *
     * <p>Called before the run transitions back to {@code ASSISTANT} — the
     * inner loop continues with the model seeing the failure.</p>
     */
    private void failTruncatedToolCalls(LaneState lane) {
        var toolCalls = HarnessUtils.extractToolCalls(lane.partial);
        for (var call : toolCalls) {
            var toolEntry = new Entry.Message(
                UUID.randomUUID().toString(), 0, null, null,
                new Message.ToolResultMessage(
                    call.toolCallId(), call.toolName(),
                    List.of(new ContentBlock.TextContent(
                        "Tool call \"" + call.toolName()
                            + "\" was not executed: the response hit the output token limit, "
                            + "so its arguments may be truncated. Re-issue the tool call with "
                            + "complete arguments.")),
                    true),
                null);
            lane.transcript.add(toolEntry);
            lane.pendingWrites.add(toolEntry);
        }
    }

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

}
