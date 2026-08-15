package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.context.OverflowDetector;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.CompactionContext;
import com.pijava.agent.hook.RequestContext;
import com.pijava.agent.hook.ResponseContext;
import com.pijava.agent.hook.RunContext;
import com.pijava.agent.hook.RunEndContext;
import com.pijava.agent.prompt.SystemPromptBuilder;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.ReplayKind;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.ai.AbortSignal;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.Usage;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Executes individual {@link Action} subclasses for {@link AgentHarness}.
 *
 * <p>Package-private — only {@code AgentHarness} creates and calls this.
 * Extracted from {@code AgentHarness} in Phase 2c to keep file sizes
 * under the 500-line limit.</p>
 */
final class ActionExecutor {

    private final ExecutionContext ctx;
    private final ToolExecutionPipeline toolPipeline;

    ActionExecutor(ExecutionContext ctx) {
        this.ctx = ctx;
        this.toolPipeline = new ToolExecutionPipeline(ctx);
    }

    // ═══════════════════════════════════════════════════════════
    // Run initiation
    // ═══════════════════════════════════════════════════════════

    /** Initiate a new run on the specified lane. */
    Action run(String laneName, String prompt) {
        var lane = ctx.requireLane(laneName);
        if (!(lane.phase instanceof RunPhase.Idle)) {
            throw new IllegalStateException("Cannot start run: lane " + laneName + " is not idle");
        }
        lane.runId = UUID.randomUUID().toString();
        lane.stepIndex = 0;
        lane.partial = null;
        lane.newestOwn = null;
        lane.transcript.clear();
        lane.pendingWrites.clear();
        lane.records.clear();
        lane.pendingToolCalls.clear();
        lane.abortSignal = AbortSignal.create();

        // Fire before_run hook
        var promptList = List.<Message>of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent(prompt))));
        ctx.hookSystem().fireBeforeRun(laneName,
            new RunContext(laneName, lane.runId, promptList));

        // Write user message entry
        var userEntry = new Entry.Message(
            UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(prompt))), null);
        lane.transcript.add(userEntry);
        lane.pendingWrites.add(userEntry);

        // Write thinking level change if non-default
        if (ctx.thinkingLevel().get() instanceof ModelThinkingLevel.Enabled en) {
            var tlEntry = new Entry.ThinkingLevelChange(
                UUID.randomUUID().toString(), 0, null, null,
                en.level().label());
            lane.transcript.add(tlEntry);
            lane.pendingWrites.add(tlEntry);
        }

        lane.phase = RunPhase.ASSISTANT;
        lane.records.add(new LaneRecord.OperationStarted(
            UUID.randomUUID().toString(), 0, laneName, null, null,
            new LaneRecord.OperationStarted.Run(promptList, List.of(), null, null)));
        ctx.incrementTurn();
        ctx.publishState(laneName);
        return peekAction(laneName);
    }

    /** Compact the specified lane's transcript. */
    void compact(String laneName, CompactionSettings settings) {
        var lane = ctx.requireLane(laneName);
        if (lane.transcript.size() <= 1) {
            throw new AgentHarness.NothingToCompactException(laneName);
        }
        applyCompaction(laneName, lane, settings, CompactionService.estimateTokens(lane.transcript));
        ctx.publishState(laneName);
    }

    /** Fire before_compaction, compute the compacted transcript, and replace it. */
    private void applyCompaction(String laneName, LaneState lane,
                                 CompactionSettings settings, int estimatedTokens) {
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
    }

    private List<Entry> compactTranscript(LaneState lane, CompactionSettings settings) {
        var result = CompactionService.compact(lane.transcript, settings, SummaryGenerator.truncating());
        var retainedTail = keptMessagesFrom(lane.transcript, result.firstKeptEntryId());
        var compactionEntry = new Entry.Compaction(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            java.time.Instant.now(), result.summary(), retainedTail,
            (int) result.tokensBefore(), result.details(), result.usage());
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
                    yield run(laneName, String.join("\n\n", steer));
                }
                var nextRun = ctx.queueManager().drainNextRun(laneName);
                if (!nextRun.isEmpty()) {
                    yield run(laneName, String.join("\n\n", nextRun));
                }
                var followUps = ctx.queueManager().drainFollowUp(laneName);
                if (!followUps.isEmpty()) {
                    yield run(laneName, String.join("\n\n", followUps));
                }
                yield null;
            }
            case RunPhase.Assistant a -> {
                var pw = drainNextPendingWrite(lane);
                if (pw != null) yield pw;
                if (!lane.pendingToolCalls.isEmpty()) {
                    if (ctx.toolExecution().get() instanceof ToolExecution.Parallel
                            && lane.pendingToolCalls.size() > 1) {
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
            case Action.AppendEntry ae -> executeAppendEntry(lane, ae);
            case Action.TryFinishRun tfr -> executeTryFinishRun(laneName, lane, tfr);
            case Action.ExecuteTool et -> executeTool(laneName, lane, et);
            case Action.ExecuteToolBatch etb -> executeToolBatch(laneName, lane, etb);
        };
    }

    /** Append queued steering prompts as user entries (Phase 3). */
    private void injectUserMessages(LaneState lane, List<String> prompts) {
        for (var prompt : prompts) {
            var userEntry = new Entry.Message(
                UUID.randomUUID().toString(), 0, null, null,
                new Message.UserMessage(List.of(new ContentBlock.TextContent(prompt))), null);
            lane.transcript.add(userEntry);
            lane.pendingWrites.add(userEntry);
        }
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
        } catch (Exception e) {
            streamError = e;
            lane.partial = AssistantMessage.empty().withStopReason("error");
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
            var parentId = lane.lastEntry() != null ? lane.lastEntry().id() : null;
            var asstEntry = new Entry.Message(
                asstEntryId, 0, null, null,
                new Message.AssistantMessage(lane.partial.content()), null);
            lane.transcript.add(asstEntry);
            lane.pendingWrites.add(asstEntry);
        }

        lane.records.add(new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            StepKind.ASSISTANT, attemptIdx, asstEntryId == null ? "" : asstEntryId, null));
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
        if ("tool_use".equals(status)) {
            List<Action.ExecuteTool> toolActions = HarnessUtils.extractToolCalls(lane.partial);
            if (!toolActions.isEmpty()) {
                lane.pendingToolCalls.addAll(toolActions);
                lane.phase = RunPhase.ASSISTANT;
                lane.records.add(new LaneRecord.OperationFinished(
                    UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                    OperationOutcome.COMPLETED, null));
                return peekAction(laneName);
            }
            // tool_use stop reason but no tool calls → complete the run instead
            status = "completed";
        }

        // Terminal outcome (completed / error): fire before_run_end and finish
        lane.records.add(new LaneRecord.OperationFinished(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            "error".equals(status) ? OperationOutcome.FAILED : OperationOutcome.COMPLETED, null));
        ctx.hookSystem().fireBeforeRunEnd(laneName,
            new RunEndContext(laneName, lane.runId, status));

        lane.phase = RunPhase.IDLE;
        // Start the next run from queued follow-up messages (Phase 3).
        // One-at-a-time leaves the rest queued; they are drained when each
        // subsequent run finishes.
        var followUps = ctx.queueManager().drainFollowUp(laneName);
        if (!followUps.isEmpty()) {
            return run(laneName, String.join("\n\n", followUps));
        }
        return null;
    }

    // ── ExecuteTool ─────────────────────────────────────────

    private Action executeTool(String laneName, LaneState lane, Action.ExecuteTool et) {
        var outcome = toolPipeline.executeStages(laneName, lane, List.of(et)).getFirst();
        toolPipeline.appendEntry(lane, et, outcome);
        lane.records.add(new LaneRecord.ToolStarted(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            "", 0, et.toolCallId(), et.toolName(), et.arguments(), "", ReplayKind.NEVER));

        if (outcome.terminate() && lane.pendingToolCalls.isEmpty()) {
            lane.pendingWrites.clear();
            lane.records.add(new LaneRecord.OperationFinished(
                UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                OperationOutcome.COMPLETED, null));
            lane.phase = RunPhase.IDLE;
            return null;
        }
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);
    }

    /** Execute a batch of tool calls in parallel (Phase 3, ToolExecution.Parallel). */
    private Action executeToolBatch(String laneName, LaneState lane,
                                     Action.ExecuteToolBatch batch) {
        var outcomes = toolPipeline.executeStages(laneName, lane, batch.calls());
        boolean anyTerminate = false;
        for (int i = 0; i < batch.calls().size(); i++) {
            var call = batch.calls().get(i);
            var outcome = outcomes.get(i);
            toolPipeline.appendEntry(lane, call, outcome);
            lane.records.add(new LaneRecord.ToolStarted(
                UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                "", 0, call.toolCallId(), call.toolName(), call.arguments(), "", ReplayKind.NEVER));
            anyTerminate |= outcome.terminate();
        }
        if (anyTerminate) {
            lane.pendingWrites.clear();
            lane.records.add(new LaneRecord.OperationFinished(
                UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                OperationOutcome.COMPLETED, null));
            lane.phase = RunPhase.IDLE;
            return null;
        }
        lane.phase = RunPhase.ASSISTANT;
        return peekAction(laneName);
    }

    // ═══════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════

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
        for (var entry : lane.transcript) {
            if (entry instanceof Entry.Message msg) {
                messages.add(msg.message());
            }
        }
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

}