package com.pijava.agent.harness;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.context.OverflowDetector;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.RequestContext;
import com.pijava.agent.hook.ResponseContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.Usage;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.telemetry.SpanOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Action.StreamAssistant} step: one streaming LLM round for a lane,
 * including auto-compaction, context overflow recovery, the attempt/usage
 * records and the transition back to {@code CHECKPOINT}.
 *
 * <p>Extracted from {@link ActionExecutor} to keep files under the 500-line
 * limit; package-private, like its former host.</p>
 */
final class AssistantStreamExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantStreamExecutor.class);

    private final ExecutionContext ctx;
    private final ContextAssembler contextAssembler;
    private final CompactionExecutor compactions;
    private final PeekAction peekAction;

    AssistantStreamExecutor(ExecutionContext ctx, PeekAction peekAction) {
        this.ctx = ctx;
        this.contextAssembler = new ContextAssembler(ctx);
        this.compactions = new CompactionExecutor(ctx);
        this.peekAction = peekAction;
    }

    /** Run one assistant stream round on the lane and return the next action. */
    Action execute(String laneName, LaneState lane, Action.StreamAssistant sa) {
        if (lane.abortSignal != null && lane.abortSignal.isAborted()) {
            lane.phase = RunPhase.CHECKPOINT;
            lane.partial = AssistantMessage.empty().withStopReason("aborted");
            lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
            return peekAction.apply(laneName);
        }

        // Auto-compaction: check token budget before building messages
        contextAssembler.applyPendingTurnUpdate(laneName, lane);
        compactions.checkAutoCompact(laneName, lane);

        var messages = contextAssembler.buildMessagesForLane(laneName, lane);
        // 系统提示不在消息列表里（pi 的 Message 没有 system 角色）——
        // 它走 Context.systemPrompt，这里算一次供钩子与请求共用。
        var systemPrompt = contextAssembler.buildSystemPrompt(lane);
        var thinkingConfig = ctx.thinkingLevelMap().forLevel(ctx.thinkingLevel().get());

        // Fire before_request
        ctx.hookSystem().fireBeforeRequest(laneName,
            new RequestContext(laneName, lane.runId, systemPrompt, messages));

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
            thinkingConfig);
        // 系统提示与工具只在 Context 上（pi 的 AgentContext）；消息列表里没有 system 角色。
        var llmContext = new Context(systemPrompt, messages, toolDefs);

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
                var iter = ctx.streamFn().stream(ctx.model().get(), llmContext, options);
                try {
                    while (iter.hasNext()) {
                        if (lane.abortSignal != null && lane.abortSignal.isAborted()) {
                            iter.close();
                            // 中断的轮次必须定格为 aborted（docs/23 §4.4）：provider 不会为
                            // 未完成的流写 stopReason，留 null 会让 determineOutcome 记成
                            // completed，且这段半成品会被投影进后续请求的上下文。
                            lane.partial = lane.partial == null
                                ? AssistantMessage.empty().withStopReason("aborted")
                                : lane.partial.withStopReason("aborted");
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
            // 用户中止导致的流异常同样归一到 aborted，否则会记成 error ⇒ FAILED
            // （docs/23 §4.4；与上面的 break 分支同族）。
            boolean aborted = lane.abortSignal != null && lane.abortSignal.isAborted();
            lane.partial = AssistantMessage.empty().withStopReason(aborted ? "aborted" : "error");
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
                new Message.AssistantMessage(lane.partial.content(),
                    // stopReason 随 entry 落库，成为唯一真相（docs/22 D1）。
                    lane.partial.stopReason(),
                    // 无 provider 支持 deferral，此处恒为 null（docs/22 D2/P1）。
                    null), null);
            lane.transcript.add(asstEntry);
            lane.pendingWrites.add(asstEntry);
            // Written while the run is in flight ⇒ deferred (docs/22 D3).
            HarnessUtils.recordDeferredWrite(lane, asstEntry);
        }

        lane.records.add(new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            StepKind.ASSISTANT, attemptIdx, asstEntryId == null ? "" : asstEntryId, null,
            RunSpanFactory.modelLabel(ctx.model().get()), messages.size(), toolDefs.size(),
            RunSpanFactory.thinkingLabel(ctx.thinkingLevel().get()),
            (System.nanoTime() - llmStart) / 1_000_000));
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
        return peekAction.apply(laneName);
    }
}
