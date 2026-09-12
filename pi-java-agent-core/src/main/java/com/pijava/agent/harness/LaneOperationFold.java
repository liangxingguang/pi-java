package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * Pure functions rebuilding a lane's orchestration state from its record log
 * (pi's {@code harness/reducer.ts} {@code reduceLaneState}), returning a
 * {@link LaneStateFolder.FoldedState}.
 *
 * <p>Inputs are a <em>bounded slice</em>, not a full transcript: the caller
 * passes the lane's records plus the entries belonging to the open operation
 * ({@code ownEntries}) and the configuration entries. Both consumers — the
 * sentinel test and resume recovery — read the same log the live driver
 * appends to; the live driver keeps mutating {@link LaneState} in place, so
 * the fold never needs to be incremental.</p>
 *
 * <p>Extracted from {@link LaneStateFolder} to keep files under the 500-line
 * limit; package-private and stateless, like its former host.</p>
 */
final class LaneOperationFold {

    private LaneOperationFold() {}

    /**
     * Fold a lane's bounded record slice back into orchestration state.
     *
     * @param lane                 lane name (for diagnostics)
     * @param records              the lane's records, oldest first
     * @param ownEntries           entries appended by the open operation, oldest first
     * @param configurationEntries configuration entries (see {@link Entry#isConfiguration()}), oldest first
     * @throws RecordLogCorruption when the record log is internally inconsistent
     */
    static LaneStateFolder.FoldedState fold(String lane, List<LaneRecord> records,
                                            List<Entry> ownEntries,
                                            List<Entry> configurationEntries) {
        var ordered = orderBySeq(records);
        // The ids of the operation's own entries — the same slice the validator
        // checks below. Exactly one lookup reads it: is the target of a
        // deferred write already applied? Configuration entries are not added:
        // every WriteDeferred target is an Entry.Message, and isConfiguration()
        // is overridden only by ModelChange/ThinkingLevelChange/ActiveToolsChange,
        // so a configuration id could never match. On the resume path (where a
        // configuration entry may predate the operation anchor) the set stays
        // correct for the same reason.
        var appliedEntryIds = new LinkedHashSet<String>(ownEntries.size());
        ownEntries.forEach(entry -> appliedEntryIds.add(entry.id()));
        // The validator's map is narrower than pi's: pi builds it from
        // input.entries (reducer.ts:317), the operation's entries plus those
        // fetched by provisioned or referenced id, while this fold has no such
        // extra input and validates the own entries only. Widening it here
        // would reject logs pi accepts (docs/22).
        RecordLogValidator.validate(lane, ordered, ownEntries);

        var openOp = openOperation(ordered);
        var finished = lastFinish(ordered);
        // One operation-scoped slice, one deferred-write id set: both the
        // pending set and the two derived projections read the same records.
        var scopedRecords = operationScoped(ordered);
        var deferredIds = deferredWriteIds(scopedRecords);
        return new LaneStateFolder.FoldedState(
            lane,
            openOp == null ? RunPhase.IDLE : RunPhase.CHECKPOINT,
            openOp == null ? null : openOp.id(),
            openOp == null ? 0 : stepIndex(ordered, openOp.id()),
            newestOwn(ownEntries),
            finished != null && finished.outcome() == OperationOutcome.FAILED,
            finished != null && finished.outcome() == OperationOutcome.ABORTED,
            effectiveConfiguration(configurationEntries),
            pendingQueue(ordered, QueueKind.STEER),
            pendingQueue(ordered, QueueKind.FOLLOW_UP),
            pendingQueue(ordered, QueueKind.NEXT_RUN),
            pendingWrites(scopedRecords, appliedEntryIds),
            deferred(ownEntries),
            toolBatch(ownEntries, deferredIds),
            terminalFailure(ownEntries, scopedRecords, deferredIds));
    }

    /**
     * The operation-scoped slice: records at or after the last
     * {@code OperationStarted}, or the whole slice when no operation was ever
     * started.
     *
     * <p>pi computes the pending sets from the open operation's own records
     * (pi {@code reducer.ts:539-541}), which keeps a stale write from an
     * earlier, already-finished run from resurfacing as pending. The fallback
     * covers a log that never opened an operation: the fold is then a
     * whole-slice projection, as for the queue sets.</p>
     */
    private static List<LaneRecord> operationScoped(List<LaneRecord> ordered) {
        int anchor = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) instanceof LaneRecord.OperationStarted) {
                anchor = i;
            }
        }
        return anchor < 0 ? ordered : ordered.subList(anchor, ordered.size());
    }

    /**
     * Accepted-but-not-yet-applied writes: a {@code write_deferred} whose
     * target id is absent from the recovery slice's entries.
     *
     * <p>Unlike the queue pending sets, this is NOT zeroed on abort — a
     * deferred write survives cancellation and is still applied
     * (pi reducer.ts:543-558). Retention is asserted even after the operation
     * has finished as aborted, which is stronger than pi's own case (pi keeps
     * the operation open, {@code reducer.test.ts:808-830}).</p>
     */
    private static List<ProvisionedEntry<?>> pendingWrites(
            List<LaneRecord> operationRecords, Set<String> ownEntryIds) {
        List<ProvisionedEntry<?>> pending = new ArrayList<>();
        for (var record : operationRecords) {
            if (record instanceof LaneRecord.WriteDeferred write
                    && !ownEntryIds.contains(write.target().entry().id())) {
                pending.add(write.target());
            }
        }
        return pending;
    }

    /**
     * The ids of every deferred write this operation requested — deferred, not
     * pending: the set includes writes that already landed.
     *
     * <p>Not part of {@link LaneStateFolder.FoldedState}: its consumers are the
     * tool-batch and terminal-failure derivations (pi {@code reducer.ts:479-486},
     * {@code reducer.ts:611-613}), which need it so a deferred write is never
     * mistaken for a tool result or for a fatal error.</p>
     */
    static Set<String> deferredWriteIds(List<LaneRecord> operationRecords) {
        Set<String> ids = new LinkedHashSet<>();
        for (var record : operationRecords) {
            if (record instanceof LaneRecord.WriteDeferred write) {
                ids.add(write.target().entry().id());
            }
        }
        return ids;
    }

    /**
     * The provider handle of an unredeemed deferred response: only the
     * newest own entry counts, so a follow-on entry redeems it
     * (pi reducer.ts:595-603).
     */
    private static DeferredHandle deferred(List<Entry> ownEntries) {
        if (ownEntries.isEmpty()) {
            return null;
        }
        var newest = ownEntries.get(ownEntries.size() - 1);
        if (newest instanceof Entry.Message msg
                && msg.message() instanceof Message.AssistantMessage assistant
                && "deferred".equals(assistant.stopReason())) {
            return assistant.deferred();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    // Derived projections
    // ═══════════════════════════════════════════════════════════

    /** A tool batch: the assistant entry that requested calls, and each call's outcome. */
    record ToolBatch(String assistantEntryId, List<ToolBatchCall> calls) {
        ToolBatch {
            calls = List.copyOf(calls);
        }
    }

    /** One call of a batch, answered or not. */
    record ToolBatchCall(String toolCallId, String toolName, String resultEntryId) {

        /**
         * True when no result entry was found for this call.
         *
         * <p>Derived rather than stored: {@code resultEntryId} is the single
         * source of truth, so the two cannot disagree.</p>
         */
        boolean missing() {
            return resultEntryId == null;
        }
    }

    /**
     * Pair the newest assistant entry's tool calls with their results
     * (pi {@code deriveToolBatch}, reducer.ts:479-486).
     *
     * <p>Results are matched by {@code toolCallId}, not by queue-drain
     * correspondence — a tool result is always one entry per call, so the
     * merged-drain shape that blocks queue inference elsewhere does not apply
     * here. Entries are compared by position, not {@code seq}: an uncommitted
     * entry's seq is 0.</p>
     *
     * <p>This scan is pi's {@code blockedResult} arm, and pi's other arm
     * ({@code startedResult}, {@code reducer.ts:473-481}) is deliberately not
     * ported. That arm looks up a {@code tool_started} record's
     * {@code resultEntryId} via a map keyed on
     * {@code record.assistantEntryId == assistantEntry.id}; pi-java emits
     * {@code ToolStarted} with an empty {@code assistantEntryId}
     * ({@code ToolExecutionPipeline.closeToolSpan}), so the key never matches
     * an entry id and {@code startedResult} would always be undefined — the
     * lookup is dead code here. Dropping it also drops a real behavior
     * difference: pi reads that map from the wider
     * {@code input.entries ∪ ownEntries} set ({@code reducer.ts:511-512}) and
     * does not filter it by {@code deferredWriteIds}, unlike
     * {@code blockedResult}.</p>
     */
    private static ToolBatch toolBatch(List<Entry> ownEntries, Set<String> deferredWriteIds) {
        int assistantIndex = -1;
        List<ContentBlock.ToolUseContent> calls = List.of();
        for (int i = ownEntries.size() - 1; i >= 0; i--) {
            if (ownEntries.get(i) instanceof Entry.Message msg
                    && msg.message() instanceof Message.AssistantMessage assistant) {
                var toolCalls = assistant.content().stream()
                    .filter(ContentBlock.ToolUseContent.class::isInstance)
                    .map(ContentBlock.ToolUseContent.class::cast)
                    .toList();
                if (!toolCalls.isEmpty()) {
                    assistantIndex = i;
                    calls = toolCalls;
                    break;
                }
            }
        }
        if (assistantIndex < 0) {
            return null;
        }
        List<ToolBatchCall> matched = new ArrayList<>();
        for (var call : calls) {
            String resultEntryId = null;
            for (int i = assistantIndex + 1; i < ownEntries.size(); i++) {
                if (ownEntries.get(i) instanceof Entry.Message msg
                        && msg.message() instanceof Message.ToolResultMessage result
                        && call.id().equals(result.toolUseId())
                        && !deferredWriteIds.contains(msg.id())) {
                    resultEntryId = msg.id();
                    break;
                }
            }
            matched.add(new ToolBatchCall(call.id(), call.name(), resultEntryId));
        }
        return new ToolBatch(((Entry.Message) ownEntries.get(assistantIndex)).id(), matched);
    }

    /**
     * The {@code source} of a failure produced by an assistant step
     * (pi {@code TerminalFailureState.source}, {@code reducer.ts:62-66}).
     */
    static final String SOURCE_STEP = "step";

    /**
     * The {@code source} of a failure produced by a deferred fetch; the value
     * is shared with the usage record that evidences the fetch.
     */
    static final String SOURCE_DEFERRED_FETCH = UsageCause.DEFERRED_FETCH.value();

    /** The newest error entry, with the provenance that explains how it arose. */
    record TerminalFailure(String entryId, String source, Message message) {}

    /**
     * Attribute an error entry to what produced it (pi reducer.ts:614-640).
     *
     * <p>An error entry counts only if a step attempt or a deferred fetch
     * produced it — otherwise the error is not the operation's terminal
     * failure. An applied deferred write is excluded outright, so a write that
     * happens to be an error message is never mistaken for a failure.</p>
     */
    private static TerminalFailure terminalFailure(List<Entry> ownEntries,
                                                   List<LaneRecord> operationRecords,
                                                   Set<String> deferredWriteIds) {
        if (ownEntries.isEmpty()) {
            return null;
        }
        var newest = ownEntries.get(ownEntries.size() - 1);
        if (!(newest instanceof Entry.Message msg)
                || !(msg.message() instanceof Message.AssistantMessage assistant)
                || !"error".equals(assistant.stopReason())
                || deferredWriteIds.contains(msg.id())) {
            return null;
        }
        boolean producedByStep = false;
        boolean producedByDeferredFetch = false;
        for (var record : operationRecords) {
            if (record instanceof LaneRecord.StepAttempt step
                    && msg.id().equals(step.resultEntryId())) {
                producedByStep = true;
            }
            if (record instanceof LaneRecord.UsageRecord usage
                    && usage.cause() == UsageCause.DEFERRED_FETCH
                    && msg.id().equals(usage.entryId())) {
                producedByDeferredFetch = true;
            }
        }
        if (ownEntries.size() >= 2
                && ownEntries.get(ownEntries.size() - 2) instanceof Entry.Message previous
                && previous.message() instanceof Message.AssistantMessage prevAssistant
                && "deferred".equals(prevAssistant.stopReason())) {
            producedByDeferredFetch = true;
        }
        if (producedByStep || producedByDeferredFetch) {
            return new TerminalFailure(msg.id(),
                producedByStep ? SOURCE_STEP : SOURCE_DEFERRED_FETCH, msg.message());
        }
        return null;
    }

    /** The operation started without a matching finish, if any. */
    private static LaneRecord.OperationStarted openOperation(List<LaneRecord> ordered) {
        LaneRecord.OperationStarted open = null;
        for (var record : ordered) {
            if (record instanceof LaneRecord.OperationStarted started) {
                open = started;
            } else if (record instanceof LaneRecord.OperationFinished finished
                    && open != null && finished.runId().equals(open.id())) {
                open = null;
            }
        }
        return open;
    }

    /** The most recent finish, or null when no operation ever finished. */
    private static LaneRecord.OperationFinished lastFinish(List<LaneRecord> ordered) {
        LaneRecord.OperationFinished last = null;
        for (var record : ordered) {
            if (record instanceof LaneRecord.OperationFinished finished) {
                last = finished;
            }
        }
        return last;
    }

    /**
     * Assistant steps completed by the operation. Compaction steps are
     * excluded: the live driver's {@code stepIndex} counts LLM rounds only.
     */
    private static int stepIndex(List<LaneRecord> ordered, String runId) {
        int count = 0;
        for (var record : ordered) {
            if (record instanceof LaneRecord.StepAttempt step
                    && step.step() == StepKind.ASSISTANT
                    && runId.equals(step.runId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * The newest assistant message among the operation's own entries, with the
     * stop reason persisted on that entry (docs/22 D1).
     *
     * <p>Type matching rather than a {@code role()} string compare: an
     * assistant transcript entry is always a {@link Message.AssistantMessage},
     * matching the live driver's {@code deriveNewestOwn}. Entries written
     * before the stop reason was persisted decode it as {@code null}.</p>
     */
    private static LaneState.NewestOwn newestOwn(List<Entry> ownEntries) {
        for (int i = ownEntries.size() - 1; i >= 0; i--) {
            if (ownEntries.get(i) instanceof Entry.Message msg
                    && msg.message() instanceof Message.AssistantMessage assistant) {
                return new LaneState.NewestOwn(
                    msg.id(), "message", "assistant", assistant.stopReason());
            }
        }
        return null;
    }

    /** Overlay the configuration entries in sequence order. */
    private static LaneStateFolder.EffectiveConfiguration effectiveConfiguration(
            List<Entry> configurationEntries) {
        ModelId<?> model = null;
        String thinkingLevel = null;
        List<String> activeToolNames = null;
        for (var entry : configurationEntries.stream()
                .sorted(Comparator.comparingLong(Entry::seq)).toList()) {
            switch (entry) {
                case Entry.ModelChange mc -> model = ModelId.of(mc.provider(), mc.modelId());
                case Entry.ThinkingLevelChange tlc -> thinkingLevel = tlc.thinkingLevel();
                case Entry.ActiveToolsChange atc ->
                    activeToolNames = List.copyOf(atc.activeToolNames());
                default -> { }
            }
        }
        return new LaneStateFolder.EffectiveConfiguration(model, thinkingLevel, activeToolNames);
    }

    /**
     * Queue items enqueued but neither cancelled nor consumed, in enqueue
     * order.
     *
     * <p>An item leaves the pending set only via an explicit
     * {@code QueueCancelled} or {@code QueueConsumed} record — including
     * {@code nextRun} items, whose prompt is merged into the run it starts
     * (pi infers this from entry presence instead, which pi-java's merged
     * drain entry cannot express; docs/21 D4/D10).</p>
     */
    private static List<LaneInfo.QueuedItem> pendingQueue(List<LaneRecord> ordered,
                                                          QueueKind kind) {
        Map<String, LaneRecord.QueueEnqueued> pending = new LinkedHashMap<>();
        for (var record : ordered) {
            if (record instanceof LaneRecord.QueueEnqueued enqueued) {
                pending.put(enqueued.target().entry().id(), enqueued);
            } else if (record instanceof LaneRecord.QueueCancelled cancelled) {
                pending.remove(cancelled.entryId());
            } else if (record instanceof LaneRecord.QueueConsumed consumed) {
                consumed.targets().forEach(t -> pending.remove(t.entry().id()));
            }
        }
        List<LaneInfo.QueuedItem> items = new ArrayList<>();
        for (var enqueued : pending.values()) {
            if (enqueued.queue() == kind) {
                var item = toQueuedItem(enqueued.target());
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    /**
     * Rebuild the queued prompt from its provisioned target. The entry id is
     * the item's queue sequence number (docs/21 R6); targets that are not
     * messages are dropped rather than failing the whole fold.
     */
    private static LaneInfo.QueuedItem toQueuedItem(ProvisionedEntry<?> target) {
        if (!(target.entry() instanceof Entry.Message msg)) {
            return null;
        }
        List<ContentBlock> content = msg.message().content();
        String prompt = content.stream()
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(block -> ((ContentBlock.TextContent) block).text())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        List<PromptImage> images = content.stream()
            .filter(ContentBlock.ImageContent.class::isInstance)
            .map(block -> (ContentBlock.ImageContent) block)
            .map(image -> new PromptImage(image.mediaType(), image.data()))
            .toList();
        long seq;
        try {
            seq = Long.parseLong(target.entry().id());
        } catch (NumberFormatException e) {
            seq = 0;
        }
        return new LaneInfo.QueuedItem(prompt, images, seq);
    }

    /** Stable ordering: persisted records sort by seq, in-memory ones keep order. */
    static List<LaneRecord> orderBySeq(List<LaneRecord> records) {
        return records.stream().sorted(Comparator.comparingLong(LaneRecord::seq)).toList();
    }
}
