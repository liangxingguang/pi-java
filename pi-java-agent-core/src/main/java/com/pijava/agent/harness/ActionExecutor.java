package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.context.OverflowDetector;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.hook.CompactionContext;
import com.pijava.agent.hook.RequestContext;
import com.pijava.agent.hook.ResponseContext;
import com.pijava.agent.hook.RunContext;
import com.pijava.agent.hook.RunEndContext;
import com.pijava.agent.hook.ToolCallContext;
import com.pijava.agent.hook.ToolResultContext;
import com.pijava.agent.prompt.SystemPromptBuilder;
import com.pijava.agent.record.LaneRecord;
import com.pijava.ai.AbortSignal;
import com.pijava.agent.tool.AgentTool;
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

    ActionExecutor(ExecutionContext ctx) {
        this.ctx = ctx;
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
        lane.runId = java.util.UUID.randomUUID().toString();
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
            Entry.newHeader(lane.nextSeq(), ""),
            "user",
            List.of(new ContentBlock.TextContent(prompt))
        );
        lane.transcript.add(userEntry);
        lane.pendingWrites.add(new ProvisionedEntry(userEntry));

        // Write thinking level change if non-default
        if (ctx.thinkingLevel().get() instanceof ModelThinkingLevel.Enabled en) {
            var tlEntry = new Entry.ThinkingLevelChange(
                Entry.newHeader(lane.nextSeq(), userEntry.header().id()),
                en.level().label()
            );
            lane.transcript.add(tlEntry);
            lane.pendingWrites.add(new ProvisionedEntry(tlEntry));
        }

        lane.phase = RunPhase.ASSISTANT;
        lane.records.add(new LaneRecord.OperationStarted(
            LaneRecord.newHeader(lane.records.size()), lane.runId, prompt));
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
            compacted = CompactionService.compact(lane.transcript, settings,
                lane.nextSeq(), HarnessUtils.lastEntryId(lane));
        }
        lane.transcript.clear();
        lane.transcript.addAll(compacted);
    }

    // ═══════════════════════════════════════════════════════════
    // Manual drive
    // ═══════════════════════════════════════════════════════════

    /** Return the next pending action from the specified lane. */
    Action peekAction(String laneName) {
        var lane = ctx.requireLane(laneName);
        return switch (lane.phase) {
            case RunPhase.Idle i -> null;
            case RunPhase.Assistant a -> {
                var pw = drainNextPendingWrite(lane);
                if (pw != null) yield pw;
                if (!lane.pendingToolCalls.isEmpty()) {
                    yield lane.pendingToolCalls.remove(0);
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
        };
    }

    private Action drainNextPendingWrite(LaneState lane) {
        if (!lane.pendingWrites.isEmpty()) {
            var pw = lane.pendingWrites.get(0);
            return new Action.AppendEntry(HarnessUtils.entryTypeName(pw.entry()), pw.entry().header().id());
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

        lane.records.add(new LaneRecord.StepAttempt(
            LaneRecord.newHeader(lane.records.size()), attemptIdx, inputTokens, outputTokens));
        if (inputTokens > 0 || outputTokens > 0) {
            lane.records.add(new LaneRecord.UsageRecord(
                LaneRecord.newHeader(lane.records.size()),
                inputTokens, outputTokens, ctx.model().get().modelName()));
            ctx.addTokens(inputTokens + outputTokens);
        }

        if (lane.partial != null) {
            var parentId = lane.lastEntry() != null
                ? lane.lastEntry().header().id() : "";
            var asstEntry = new Entry.Message(
                Entry.newHeader(lane.nextSeq(), parentId),
                "assistant", lane.partial.content());
            lane.transcript.add(asstEntry);
            lane.pendingWrites.add(new ProvisionedEntry(asstEntry));
        }

        lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
        lane.phase = RunPhase.CHECKPOINT;
        return peekAction(laneName);
    }

    // ── AppendEntry ─────────────────────────────────────────

    private Action executeAppendEntry(LaneState lane, Action.AppendEntry ae) {
        for (var pw : lane.pendingWrites) {
            if (pw.entry().header().id().equals(ae.entryId())) {
                pw.markWritten();
                break;
            }
        }
        lane.pendingWrites.removeIf(ProvisionedEntry::isWritten);
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
                    LaneRecord.newHeader(lane.records.size()), lane.runId, "tool_use"));
                return peekAction(laneName);
            }
            // tool_use stop reason but no tool calls → complete the run instead
            status = "completed";
        }

        // Terminal outcome (completed / error): fire before_run_end and finish
        lane.records.add(new LaneRecord.OperationFinished(
            LaneRecord.newHeader(lane.records.size()), lane.runId, status));
        ctx.hookSystem().fireBeforeRunEnd(laneName,
            new RunEndContext(laneName, lane.runId, status));

        lane.phase = RunPhase.IDLE;
        return null;
    }

    // ── ExecuteTool ─────────────────────────────────────────

    private Action executeTool(String laneName, LaneState lane, Action.ExecuteTool et) {
        // Fire before_tool
        var beforeResult = ctx.hookSystem().fireBeforeTool(laneName,
            new ToolCallContext(laneName, et.toolCallId(), et.toolName(), et.arguments()));
        if (beforeResult != null && !beforeResult.allowed()) {
            var deniedEntry = new Entry.Message(
                Entry.newHeader(lane.nextSeq(),
                    HarnessUtils.lastEntryId(lane)),
                "tool",
                List.of(new ContentBlock.ToolResultContent(
                    et.toolCallId(), et.toolName(),
                    List.of(new ContentBlock.TextContent("Tool call denied by hook")), true)));
            lane.transcript.add(deniedEntry);
            lane.pendingWrites.add(new ProvisionedEntry(deniedEntry));
            lane.phase = RunPhase.ASSISTANT;
            return peekAction(laneName);
        }

        Map<String, Object> args = (beforeResult != null && beforeResult.arguments() != null)
            ? beforeResult.arguments() : et.arguments();

        List<ContentBlock> resultBlocks;
        boolean isError = false;
        boolean shouldTerminate = false;

        try {
            var result = ctx.toolExecutor().executeRaw(
                et.toolName(), et.toolCallId(), args, lane.abortSignal);

            // Fire after_tool
            var afterResult = ctx.hookSystem().fireAfterTool(laneName,
                new ToolResultContext(laneName, et.toolCallId(), et.toolName(), result));
            if (afterResult != null) {
                result = afterResult;
            }

            resultBlocks = result.content();
            shouldTerminate = result.terminate();
        } catch (SecurityException e) {
            resultBlocks = List.of(new ContentBlock.TextContent(
                "Tool call not approved: " + e.getMessage()));
            isError = true;
        } catch (IllegalArgumentException e) {
            resultBlocks = List.of(new ContentBlock.TextContent(
                "Tool error: " + e.getMessage()));
            isError = true;
        } catch (Exception e) {
            resultBlocks = List.of(new ContentBlock.TextContent(
                "Error: " + e.getMessage()));
            isError = true;
        }

        var parentId = lane.lastEntry() != null
            ? lane.lastEntry().header().id() : "";
        var toolEntry = new Entry.Message(
            Entry.newHeader(lane.nextSeq(), parentId),
            "tool",
            List.of(new ContentBlock.ToolResultContent(
                et.toolCallId(), et.toolName(), resultBlocks, isError)));
        lane.transcript.add(toolEntry);
        lane.pendingWrites.add(new ProvisionedEntry(toolEntry));

        lane.records.add(new LaneRecord.ToolStarted(
            LaneRecord.newHeader(lane.records.size()),
            et.toolCallId(), et.toolName(), args));

        if (shouldTerminate && lane.pendingToolCalls.isEmpty()) {
            lane.pendingWrites.clear();
            lane.records.add(new LaneRecord.OperationFinished(
                LaneRecord.newHeader(lane.records.size()), lane.runId, "completed"));
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
        if (estimatedTokens > settings.maxTokens()) {
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
                messages.add(HarnessUtils.toMessage(msg));
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
