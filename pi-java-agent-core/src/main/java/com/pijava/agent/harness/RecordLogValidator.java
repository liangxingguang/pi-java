package com.pijava.agent.harness;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.record.StepKind;

/**
 * The corruption rules of a lane's record log, restricted to the subset of
 * pi's rules that need no entry lookups (docs/21 §3.4, R8).
 *
 * <p>Extracted from {@link LaneStateFolder} to keep files under the 500-line
 * limit; package-private and stateless, like its former host.</p>
 */
final class RecordLogValidator {

    private RecordLogValidator() {}

    /** Compaction reasons accepted by the record log (pi {@code compactionReason}). */
    private static final Set<String> COMPACTION_REASONS =
        Set.of("manual", "threshold", "overflow");

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
    static void validate(String lane, List<LaneRecord> records) {
        var ordered = LaneOperationFold.orderBySeq(records);
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

            String runId = LaneOperationFold.runIdOf(record);
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

    private static void corrupt(String code, String message) {
        throw RecordLogCorruption.of(code, message);
    }
}
