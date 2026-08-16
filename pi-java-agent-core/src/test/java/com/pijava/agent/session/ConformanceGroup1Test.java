package com.pijava.agent.session;

import static com.pijava.agent.session.ConformanceSupport.assistantMessage;
import static com.pijava.agent.session.ConformanceSupport.operationFinished;
import static com.pijava.agent.session.ConformanceSupport.operationStarted;
import static com.pijava.agent.session.ConformanceSupport.queueCancelled;
import static com.pijava.agent.session.ConformanceSupport.queueEnqueued;
import static com.pijava.agent.session.ConformanceSupport.stepAttempt;
import static com.pijava.agent.session.ConformanceSupport.textOf;
import static com.pijava.agent.session.ConformanceSupport.toolResult;
import static com.pijava.agent.session.ConformanceSupport.toolStarted;
import static com.pijava.agent.session.ConformanceSupport.usage;
import static com.pijava.agent.session.ConformanceSupport.userMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;

import org.junit.jupiter.api.Test;

/**
 * Conformance group 1 — entries and lanes (8) + group 2 — records and log (8).
 * The same assertions run against Memory / JSONL / SQLite backends.
 */
public abstract class ConformanceGroup1Test {

    protected abstract ConformanceBackend backend();

    // ══════════ Group 1: entries and lanes ══════════

    @Test
    void sharedSequenceParentAssignmentAndEpochMsTimestamps() {
        try (var b = backend()) {
            var session = b.create("cwd");
            var e1 = session.appendEntry(userMessage("m1", "first"), "main");
            var e2 = session.appendEntry(assistantMessage("m2", "second"), "main");
            var r1 = session.appendRecord(stepAttempt("r1", "main", "run-1"));

            assertThat(e1.seq()).isEqualTo(1);
            assertThat(e2.seq()).isEqualTo(2);
            assertThat(r1.seq()).isEqualTo(3);
            assertThat(e1.parentId()).isNull();
            assertThat(e2.parentId()).isEqualTo("m1");
            assertThat(e1.timestamp()).isNotNull();
            assertThat(e2.timestamp()).isNotNull();
            assertThat(r1.timestamp()).isNotNull();
        }
    }

    @Test
    void duplicateIdThrowsAlreadyExistsAndLeavesStateUnchanged() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "first"), "main");
            var before = session.findEntries(EntryQuery.all()).size();

            assertThatThrownBy(() -> session.appendEntry(userMessage("m1", "dup"), "main"))
                .isInstanceOf(SessionError.class)
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.ALREADY_EXISTS);

            assertThat(session.findEntries(EntryQuery.all())).hasSize(before);
        }
    }

    @Test
    void laneIsolationAndSharedTree() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "root"), "main");
            session.appendEntry(assistantMessage("m2", "on main"), "main");
            session.createLane("feature", "m1");
            session.appendEntry(assistantMessage("m3", "on feature"), "feature");

            var main = session.view("main");
            var feature = session.view("feature");
            assertThat(main.getLeafId()).isEqualTo("m2");
            assertThat(feature.getLeafId()).isEqualTo("m3");

            // Shared tree: both lanes reach the root.
            var featurePath = feature.findEntriesOnBranch(EntryQuery.all(),
                BranchBounds.none());
            var mainPath = main.findEntriesOnBranch(EntryQuery.all(), BranchBounds.none());
            assertThat(featurePath.stream().map(Entry::id)).contains("m1", "m3");
            assertThat(mainPath.stream().map(Entry::id)).contains("m1", "m2");
        }
    }

    @Test
    void laneLifecycleValidation() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.createLane("feature", null);
            assertThatThrownBy(() -> session.createLane("feature", null))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.ALREADY_EXISTS);
            assertThatThrownBy(() -> session.createLane("other", "missing"))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.NOT_FOUND);
            assertThatThrownBy(() -> session.moveLane("missing", null))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.INVALID_LANE);
        }
    }

    @Test
    void viewDoesNotCacheLeaf() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "first"), "main");
            var view = session.view("main");
            assertThat(view.getLeafId()).isEqualTo("m1");
            session.appendEntry(assistantMessage("m2", "second"), "main");
            assertThat(view.getLeafId()).isEqualTo("m2");
        }
    }

    @Test
    void provisionedEntryKeepsItsId() {
        try (var b = backend()) {
            var session = b.create("cwd");
            var committed = session.appendEntry(userMessage("custom-id", "hello"), "main");
            assertThat(committed.id()).isEqualTo("custom-id");
        }
    }

    @Test
    void toolResultTerminateFlagPersists() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "do it"), "main");
            session.appendEntry(toolResult("t1", "bash", "done", false, true), "main");
            var entry = (Entry.Message) session.getEntry("t1");
            assertThat(entry.terminate()).isTrue();
        }
    }

    @Test
    void concurrentLaneWritesSerializeWithDistinctSequences() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.createLane("left", null);
            session.createLane("right", null);
            var left = new Thread(() -> {
                for (int i = 0; i < 5; i++) {
                    session.appendEntry(userMessage("l" + i, "l" + i), "left");
                }
            });
            var right = new Thread(() -> {
                for (int i = 0; i < 5; i++) {
                    session.appendEntry(userMessage("r" + i, "r" + i), "right");
                }
            });
            left.start();
            right.start();
            left.join();
            right.join();

            var seqs = session.findEntries(EntryQuery.all()).stream()
                .map(Entry::seq).sorted().toList();
            assertThat(seqs).containsExactly(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // ══════════ Group 2: records and log ══════════

    @Test
    void recordAndLaneMovesAreIndependentMutations() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "first"), "main");
            var record = session.appendRecord(stepAttempt("r1", "main", "run-1"));
            session.moveLane("main", null);
            var log = session.getLog(LogOptions.none());

            assertThat(record.seq()).isEqualTo(2);
            assertThat(log).extracting(LogItem::seq).containsExactly(1L, 2L, 3L);
            assertThat(log.get(2)).isInstanceOf(LogItem.LaneItem.class);
            assertThat(session.getLeafId()).isNull();
        }
    }

    @Test
    void laneNamePersistsAndQueueEnqueuedRestores() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.setName("fix-login");
            session.appendRecord(queueEnqueued("q1", "main", "run-1"));
            assertThat(session.getName()).isEqualTo("fix-login");
            var records = session.findRecords(RecordQuery.all());
            assertThat(records).anyMatch(r -> r.type().equals("queue_enqueued"));
        }
    }

    @Test
    void queueCancelledDoesNotConsumeTarget() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendRecord(queueCancelled("c1", "main", "target-1"));
            var record = session.findRecords(new RecordQuery(null, "queue_cancelled", null,
                null, null, null, null)).getFirst();
            assertThat(((LaneRecord.QueueCancelled) record).entryId()).isEqualTo("target-1");
            assertThat(((LaneRecord.QueueCancelled) record).runId()).isNull();
        }
    }

    @Test
    void recordsFilterByLaneTypeRunIdOrderLimit() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendRecord(stepAttempt("r1", "main", "run-1"));
            session.appendRecord(stepAttempt("r2", "main", "run-1"));
            session.appendRecord(toolStarted("r3", "main", "run-1"));
            session.createLane("feature", null);
            session.appendRecord(stepAttempt("r4", "feature", "run-2"));

            assertThat(session.findRecords(new RecordQuery("main", null, null, null,
                null, null, null))).hasSize(3);
            assertThat(session.findRecords(new RecordQuery(null, "step_attempt", null, null,
                null, null, null))).hasSize(3);
            assertThat(session.findRecords(new RecordQuery(null, null, "run-1", null,
                null, null, null))).hasSize(3);
            assertThat(session.findRecords(new RecordQuery(null, null, null, null,
                null, EntryOrder.OLDEST_FIRST, 2)))
                .extracting(LaneRecord::id).containsExactly("r1", "r2");
        }
    }

    @Test
    void operationStartedFiltersByOperationKind() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendRecord(operationStarted("op1", "main"));
            session.appendRecord(operationFinished("f1", "main", "op1"));
            session.appendRecord(compactionStartedForTest("op2", "main"));
            var runs = session.findRecords(new RecordQuery(null, "operation_started", null,
                OperationKind.RUN, null, null, null));
            var compactions = session.findRecords(new RecordQuery(null, "operation_started", null,
                OperationKind.COMPACTION, null, null, null));
            assertThat(runs).extracting(LaneRecord::id).containsExactly("op1");
            assertThat(compactions).extracting(LaneRecord::id).containsExactly("op2");
        }
    }

    @Test
    void sameLaneDoubleOpenOperationThrowsStorage() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendRecord(operationStarted("op1", "main"));
            assertThatThrownBy(() -> session.appendRecord(operationStarted("op2", "main")))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.STORAGE);
            session.appendRecord(operationFinished("f1", "main", "op1"));
            session.appendRecord(operationStarted("op3", "main"));
            assertThat(session.findOpenOperations("main", 0)).extracting(LaneRecord::id)
                .containsExactly("op3");
        }
    }

    @Test
    void outOfOrderFinishDoesNotCloseLaterStart() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendRecord(operationStarted("op1", "main"));
            // A finish for an unknown run id must not close the open operation.
            session.appendRecord(operationFinished("f1", "main", "unknown-run"));
            assertThat(session.findOpenOperations("main", 0)).extracting(LaneRecord::id)
                .containsExactly("op1");
        }
    }

    @Test
    void findOpenOperationsScopedByLaneAndLimit() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.createLane("other", null);
            session.appendRecord(operationStarted("op1", "main"));
            var limit = session.findOpenOperations("main", 1);
            assertThat(limit).extracting(LaneRecord::id).containsExactly("op1");
            assertThat(session.findOpenOperations("other", 1)).isEmpty();
        }
    }

    private static com.pijava.agent.record.NewRecord<LaneRecord.OperationStarted>
            compactionStartedForTest(String id, String lane) {
        return new com.pijava.agent.record.NewRecord<>(
            new LaneRecord.OperationStarted(id, 0, lane, null, null,
                new LaneRecord.OperationStarted.Compaction(null, "entry-0")));
    }
}
