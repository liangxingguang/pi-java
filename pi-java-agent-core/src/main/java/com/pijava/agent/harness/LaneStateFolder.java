package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;

/**
 * Pure functions rebuilding a lane's orchestration state from its record log.
 *
 * <p>Aligned with pi's {@code harness/reducer.ts} ({@code reduceLaneState}),
 * restricted to the subset pi-java can derive: operation state, queue pending
 * sets and the effective configuration. Deferred writes, tool batches and
 * terminal-failure provenance are out of scope (docs/21 §6).</p>
 *
 * <p>Inputs are a <em>bounded slice</em>, not a full transcript: the caller
 * passes the lane's records plus the entries belonging to the open operation
 * ({@code ownEntries}) and the configuration entries. Both consumers — the
 * sentinel test and resume recovery — read the same log the live driver
 * appends to; the live driver keeps mutating {@link LaneState} in place, so
 * the fold never needs to be incremental.</p>
 */
final class LaneStateFolder {

    private LaneStateFolder() {}

    /** Compaction reasons accepted by the record log (pi {@code compactionReason}). */
    private static final Set<String> COMPACTION_REASONS =
        Set.of("manual", "threshold", "overflow");

    // ═══════════════════════════════════════════════════════════
    // Fold result
    // ═══════════════════════════════════════════════════════════

    /**
     * The folded snapshot of a lane, field-aligned with {@link LaneState}.
     *
     * <p>{@code phase} is normalized: an open operation folds to
     * {@code CHECKPOINT} rather than the transient {@code ASSISTANT} state
     * that the live driver passes through right after a tool-use stream
     * (docs/21 R5).</p>
     */
    record FoldedState(
        String lane,
        RunPhase phase,
        String runId,
        int stepIndex,
        LaneState.NewestOwn newestOwn,
        boolean faulted,
        boolean aborted,
        EffectiveConfiguration effectiveConfiguration,
        List<LaneInfo.QueuedItem> pendingSteer,
        List<LaneInfo.QueuedItem> pendingFollowUp,
        List<LaneInfo.QueuedItem> pendingNextRun
    ) {
        FoldedState {
            pendingSteer = List.copyOf(pendingSteer);
            pendingFollowUp = List.copyOf(pendingFollowUp);
            pendingNextRun = List.copyOf(pendingNextRun);
        }

        /** True when the lane has no open operation. */
        boolean idle() {
            return phase instanceof RunPhase.Idle;
        }
    }

    /**
     * Configuration derived from the configuration entries, overlaid in
     * sequence order.
     *
     * <p>A {@code null} field means "nothing recorded" — the lane inherits the
     * harness default. {@code activeToolNames} is only ever non-null when an
     * {@code ActiveToolsChange} entry exists; {@code setActiveTools} does not
     * emit one today, so a lane-level tool override is not recoverable from
     * the log (docs/21 D11).</p>
     */
    record EffectiveConfiguration(
        ModelId<?> model,
        String thinkingLevel,
        List<String> activeToolNames
    ) {
        EffectiveConfiguration {
            activeToolNames = activeToolNames == null ? null : List.copyOf(activeToolNames);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // fold
    // ═══════════════════════════════════════════════════════════

    /**
     * Fold a lane's bounded record slice back into orchestration state.
     *
     * @param lane                 lane name (for diagnostics)
     * @param records              the lane's records, oldest first
     * @param ownEntries           entries appended by the open operation, oldest first
     * @param configurationEntries configuration entries (see {@link Entry#isConfiguration()}), oldest first
     * @throws RecordLogCorruption when the record log is internally inconsistent
     */
    static FoldedState fold(String lane, List<LaneRecord> records,
                            List<Entry> ownEntries, List<Entry> configurationEntries) {
        var ordered = orderBySeq(records);
        validateRecordLog(lane, ordered);

        var openOp = openOperation(ordered);
        var finished = lastFinish(ordered);
        return new FoldedState(
            lane,
            openOp == null ? RunPhase.IDLE : RunPhase.CHECKPOINT,
            openOp == null ? null : openOp.id(),
            openOp == null ? 0 : stepIndex(ordered, openOp.id()),
            newestOwn(ownEntries, ordered),
            finished != null && finished.outcome() == OperationOutcome.FAILED,
            finished != null && finished.outcome() == OperationOutcome.ABORTED,
            effectiveConfiguration(configurationEntries),
            pendingQueue(ordered, QueueKind.STEER),
            pendingQueue(ordered, QueueKind.FOLLOW_UP),
            pendingQueue(ordered, QueueKind.NEXT_RUN));
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
     * stop reason its step attempt recorded.
     *
     * <p>The live driver reads the stop reason from the in-memory partial;
     * {@code StepAttempt.stopReason} is the persisted equivalent, so a record
     * written before Phase 21 (or a non-assistant step) yields {@code null}.</p>
     */
    private static LaneState.NewestOwn newestOwn(List<Entry> ownEntries,
                                                 List<LaneRecord> ordered) {
        for (int i = ownEntries.size() - 1; i >= 0; i--) {
            if (ownEntries.get(i) instanceof Entry.Message msg
                    && "assistant".equals(msg.message().role())) {
                return new LaneState.NewestOwn(
                    msg.id(), "message", "assistant", stopReasonFor(ordered, msg.id()));
            }
        }
        return null;
    }

    private static String stopReasonFor(List<LaneRecord> ordered, String entryId) {
        String stopReason = null;
        for (var record : ordered) {
            if (record instanceof LaneRecord.StepAttempt step
                    && step.step() == StepKind.ASSISTANT
                    && entryId.equals(step.resultEntryId())) {
                stopReason = step.stopReason();
            }
        }
        return stopReason;
    }

    /** Overlay the configuration entries in sequence order. */
    private static EffectiveConfiguration effectiveConfiguration(
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
        return new EffectiveConfiguration(model, thinkingLevel, activeToolNames);
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

    // ═══════════════════════════════════════════════════════════
    // validateRecordLog
    // ═══════════════════════════════════════════════════════════

    /**
     * Validate a lane's record log against the subset of pi's rules that need
     * no entry lookups (docs/21 §3.4, R8).
     *
     * <p>Positions, not {@code seq}, order the comparisons: records attached
     * to a live lane all carry {@code seq == 0} until storage commits them, so
     * sequence numbers cannot order in-memory records.</p>
     *
     * @throws RecordLogCorruption on the first violated rule
     */
    static void validateRecordLog(String lane, List<LaneRecord> records) {
        var ordered = orderBySeq(records);
        var starts = new LinkedHashSet<String>();
        var open = new LinkedHashSet<String>();
        Map<String, Integer> finishedAt = new HashMap<>();
        Map<String, Integer> abortedAt = new HashMap<>();
        Map<String, Integer> enqueuedAt = new LinkedHashMap<>();
        Map<String, LaneRecord.StepAttempt> attempts = new LinkedHashMap<>();

        for (int i = 0; i < ordered.size(); i++) {
            var record = ordered.get(i);
            if (record instanceof LaneRecord.OperationStarted started) {
                starts.add(started.id());
                open.add(started.id());
                continue;
            }

            String runId = runIdOf(record);
            if (runId != null) {
                if (!starts.contains(runId)) {
                    corrupt("unknown_operation", "Record " + record.id()
                        + " references unknown operation " + runId);
                }
                Integer finish = finishedAt.get(runId);
                if (finish != null && i > finish) {
                    corrupt("record_after_finish", "Record " + record.id()
                        + " follows the finish of operation " + runId);
                }
            }

            if (record instanceof LaneRecord.OperationFinished finished) {
                finishedAt.put(finished.runId(), i);
                open.remove(finished.runId());
            } else if (record instanceof LaneRecord.AbortRequested abort) {
                if (runId != null) {
                    abortedAt.put(runId, i);
                }
            } else if (record instanceof LaneRecord.StepAttempt step) {
                validateCompactionReason(step);
                validateAttemptSequence(attempts, step);
            } else if (record instanceof LaneRecord.QueueEnqueued enqueued) {
                validateQueueEnqueue(enqueued, runId, abortedAt, i);
                enqueuedAt.put(enqueued.target().entry().id(), i);
            } else if (record instanceof LaneRecord.QueueCancelled cancelled) {
                validateQueueCancellation(cancelled, enqueuedAt, i);
            }
        }

        if (open.size() > 1) {
            corrupt("multiple_open_operations",
                "Lane " + lane + " has at least two open operations");
        }
    }

    private static void validateCompactionReason(LaneRecord.StepAttempt step) {
        if (step.step() == StepKind.COMPACTION) {
            // Set.of rejects null lookups, so test for null first.
            if (step.compactionReason() == null
                    || !COMPACTION_REASONS.contains(step.compactionReason())) {
                corrupt("invalid_compaction_reason", "Compaction attempt " + step.id()
                    + " has no valid compaction reason");
            }
        } else if (step.compactionReason() != null) {
            corrupt("invalid_compaction_reason", step.step().value() + " attempt "
                + step.id() + " has a compaction reason");
        }
    }

    /** A gap in a (run, step) attempt series means an attempt was lost. */
    private static void validateAttemptSequence(Map<String, LaneRecord.StepAttempt> attempts,
                                                LaneRecord.StepAttempt step) {
        String key = step.runId() + "|" + step.step().value();
        var previous = attempts.get(key);
        int expected = previous == null ? 0 : previous.attempt() + 1;
        if (step.attempt() != expected) {
            corrupt("non_consecutive_attempt", step.step().value() + " attempt " + step.id()
                + " is " + step.attempt() + "; expected " + expected);
        }
        attempts.put(key, step);
    }

    private static void validateQueueEnqueue(LaneRecord.QueueEnqueued enqueued, String runId,
                                             Map<String, Integer> abortedAt, int position) {
        if (enqueued.queue() == QueueKind.NEXT_RUN || runId == null) {
            return;
        }
        Integer aborted = abortedAt.get(runId);
        if (aborted != null && position > aborted) {
            corrupt("queue_after_abort", enqueued.queue().value() + " item "
                + enqueued.target().entry().id() + " was enqueued after abort");
        }
    }

    /**
     * A cancellation must reference an earlier enqueue of the same item.
     *
     * <p>pi additionally requires the two records to share a run id; pi-java
     * enqueues with the lane's run id at enqueue time (often {@code null},
     * since a queue item is usually added while idle and consumed by a later
     * run), so that check would report corruption on valid logs.</p>
     */
    private static void validateQueueCancellation(LaneRecord.QueueCancelled cancelled,
                                                  Map<String, Integer> enqueuedAt, int position) {
        Integer enqueued = enqueuedAt.get(cancelled.entryId());
        if (enqueued == null || enqueued >= position) {
            corrupt("invalid_queue_cancellation", "Queue cancellation " + cancelled.id()
                + " has no pending matching enqueue");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * The run an operation-scoped record belongs to, or {@code null} when it
     * carries none (an idle enqueue, or an abort on a lane with no run).
     */
    private static String runIdOf(LaneRecord record) {
        String runId = switch (record) {
            case LaneRecord.OperationFinished r -> r.runId();
            case LaneRecord.AbortRequested r -> r.runId();
            case LaneRecord.StepAttempt r -> r.runId();
            case LaneRecord.ToolStarted r -> r.runId();
            case LaneRecord.ToolFinished r -> r.runId();
            case LaneRecord.QueueEnqueued r -> r.runId();
            case LaneRecord.QueueCancelled r -> r.runId();
            case LaneRecord.QueueConsumed r -> r.runId();
            case LaneRecord.WriteDeferred r -> r.runId();
            case LaneRecord.UsageRecord r -> r.runId();
            case LaneRecord.OperationStarted r -> null;
        };
        return runId == null || runId.isEmpty() ? null : runId;
    }

    /** Stable ordering: persisted records sort by seq, in-memory ones keep order. */
    private static List<LaneRecord> orderBySeq(List<LaneRecord> records) {
        return records.stream().sorted(Comparator.comparingLong(LaneRecord::seq)).toList();
    }

    private static void corrupt(String code, String message) {
        throw RecordLogCorruption.of(code, message);
    }
}
