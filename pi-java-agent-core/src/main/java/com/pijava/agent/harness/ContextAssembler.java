package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.prompt.SystemPromptBuilder;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Assemble the next-turn context for a lane: apply the pending turn update
 * (model / thinking-level switch entries), then build the message list —
 * system prompt (skills + tools) plus compaction-aware context entries.
 *
 * <p>Extracted from {@link ActionExecutor} in the agent-loop L1 cleanup to
 * keep files under the 500-line limit.</p>
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
                lane.pendingWrites.add(e);
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
                lane.pendingWrites.add(e);
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
     * Build the message list for the next LLM request: system prompt first,
     * then compaction-aware context entries (compaction/branch summaries become
     * user messages instead of being dropped), then transform_context hook.
     */
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
}
