package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.prompt.SystemPromptBuilder;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Assemble the next-turn context for a lane: apply the pending turn update
 * (model / thinking-level switch entries), then build the message list —
 * system prompt (skills + tools) plus compaction-aware context entries.
 *
 * <p>Extracted from the former step-chain executor in the agent-loop L1 cleanup
 * to keep files under the 500-line limit.</p>
 */
final class ContextAssembler {

    private final ExecutionContext ctx;

    ContextAssembler(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    /** Apply the queued model/thinking-level change and record change entries. */
    void applyPendingTurnUpdate(String laneName, LaneState lane) {
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
                    HarnessUtils.lastEntryId(lane), Instant.now(),
                    upd.model().provider(), upd.model().modelName());
                lane.transcript.add(e);
                // Applied mid-run by a prepare_next_turn hook ⇒ deferred.
                HarnessUtils.recordDeferredWrite(lane, e);
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
                    HarnessUtils.lastEntryId(lane), Instant.now(), upd.thinkingLevel());
                lane.transcript.add(e);
                // Applied mid-run by a prepare_next_turn hook ⇒ deferred.
                HarnessUtils.recordDeferredWrite(lane, e);
            }
        }
    }

    /** Build the system prompt (base + tools + skills) for the next request. */
    String buildSystemPrompt(LaneState lane) {
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

    /**
     * Build the message list for the next LLM request: compaction-aware context
     * entries (compaction/branch summaries become user messages instead of being
     * dropped), then the transform_context hook.
     *
     * <p><b>系统提示不在这里。</b> pi 的 {@code Message} 没有 system 角色
     * （{@code packages/ai/src/types.ts:470}），系统提示走
     * {@link Context#systemPrompt()}；{@code transformContext} 也只看得到消息
     * （pi 的钩子签名是 {@code (messages) => messages}）。系统提示由
     * {@link #buildSystemPrompt} 单独产出，宿主在 run 起点装进 {@link Context}。</p>
     */
    List<Message> buildMessagesForLane(String laneName, LaneState lane) {
        var messages = new ArrayList<Message>();
        // Compaction-aware context (pi buildContextEntries): compaction/branch
        // summaries become user messages instead of being dropped
        messages.addAll(ContextEntries.toMessages(
            ContextEntries.pathToLeaf(lane.transcript, HarnessUtils.lastEntryId(lane))));
        // Fire transform_context hook
        var transformed = ctx.hookSystem().fireTransformContext(laneName, messages);
        return new ArrayList<>(transformed);
    }
}
