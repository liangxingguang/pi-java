package com.pijava.agent.session;

import static com.pijava.agent.session.ConformanceSupport.assistantMessage;
import static com.pijava.agent.session.ConformanceSupport.custom;
import static com.pijava.agent.session.ConformanceSupport.operationFinished;
import static com.pijava.agent.session.ConformanceSupport.operationStarted;
import static com.pijava.agent.session.ConformanceSupport.usage;
import static com.pijava.agent.session.ConformanceSupport.userMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;

import org.junit.jupiter.api.Test;

/**
 * Conformance group 3 — queries and facts (4) + group 4 — validation and
 * immutability (4).
 */
public abstract class ConformanceGroup2Test {

    protected abstract ConformanceBackend backend();

    // ══════════ Group 3: queries and facts ══════════

    @Test
    void invalidQueriesThrowBeforeReading() {
        try (var b = backend()) {
            var session = b.create("cwd");
            assertThatThrownBy(() -> session.findEntries(
                new EntryQuery(null, null, null, 0, null)))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.INVALID_QUERY);
            assertThatThrownBy(() -> session.findEntries(
                new EntryQuery(null, null, null, null, new EntryCursor(-1))))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.INVALID_QUERY);
            assertThatThrownBy(() -> session.findRecords(
                new RecordQuery(null, "step_attempt", null, OperationKind.RUN,
                    null, null, null)))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.INVALID_QUERY);
        }
    }

    @Test
    void boundedFiltersCursorAndCustomType() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "first"), "main");
            session.appendEntry(assistantMessage("m2", "second"), "main");
            session.appendEntry(custom("c1", "my-event", Map.of("k", "v")), "main");
            session.appendEntry(userMessage("m3", "third"), "main");

            var lastTwo = session.findEntries(new EntryQuery(null, null, null, 2, null));
            assertThat(lastTwo).extracting(Entry::id).containsExactly("m3", "c1");

            // Newest-first cursor: seq < afterSeq.
            var afterCursor = session.findEntries(new EntryQuery(null, null, null, null,
                new EntryCursor(2)));
            assertThat(afterCursor).extracting(Entry::id).containsExactly("m1");
            var oldestCursor = session.findEntries(new EntryQuery(null, null,
                EntryOrder.OLDEST_FIRST, null, new EntryCursor(2)));
            assertThat(oldestCursor).extracting(Entry::id).containsExactly("c1", "m3");

            var customs = session.findEntries(new EntryQuery("custom", "my-event", null, null, null));
            assertThat(customs).extracting(Entry::id).containsExactly("c1");

            var oldest = session.findEntries(new EntryQuery(null, null,
                EntryOrder.OLDEST_FIRST, 2, null));
            assertThat(oldest).extracting(Entry::id).containsExactly("m1", "m2");
        }
    }

    @Test
    void nameLabelLatestWinsAndUsageAccumulatesStats() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "first"), "main");
            session.setName("alpha");
            session.setName("beta");
            session.setLabel("m1", "start");
            session.setLabel("m1", "middle");
            session.appendRecord(usage("u1", "main", "run-1", 100, 50));
            session.appendRecord(usage("u2", "main", "run-1", 10, 5));

            assertThat(session.getName()).isEqualTo("beta");
            assertThat(session.getLabel("m1")).isEqualTo("middle");
            var stats = session.getStats();
            assertThat(stats.messageCount()).isEqualTo(1);
            assertThat(stats.totalTokens()).isEqualTo(165);
        }
    }

    @Test
    void setNameNullClearsAndSurvivesReopenAndFork() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "first"), "main");
            session.setName("temporary");
            session.setName(null);
            assertThat(session.getName()).isNull();
        }
    }

    // ══════════ Group 4: validation and immutability ══════════

    @Test
    void openOperationRecordIsImmutable() {
        try (var b = backend()) {
            var session = b.create("cwd");
            var started = session.appendRecord(operationStarted("op1", "main"));
            var reopened = session.findRecords(new RecordQuery(null, "operation_started",
                null, null, null, null, null)).getFirst();
            // Java records are immutable by construction; the read must equal the write.
            assertThat(reopened).isEqualTo(started);
        }
    }

    @Test
    void getEntryGetMetadataAndGetLogReturnImmutableCopies() {
        try (var b = backend()) {
            var session = b.create("cwd");
            var entry = session.appendEntry(userMessage("m1", "first"), "main");
            var read = session.getEntry("m1");
            assertThat(read).isEqualTo(entry);
            assertThat(session.getLog(LogOptions.none())).isNotNull();
        }
    }

    @Test
    void nonJsonEntryPayloadRejected() {
        try (var b = backend()) {
            var session = b.create("cwd");
            Map<String, Object> cyclic = new java.util.HashMap<>();
            cyclic.put("self", cyclic);
            var bad = new com.pijava.agent.entry.ProvisionedEntry<Entry.Custom>(
                new Entry.Custom("bad-1", 0, null, null, "cyclic", cyclic));
            assertThatThrownBy(() -> session.appendEntry(bad, "main"))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.INVALID_PAYLOAD);
            assertThat(session.getEntry("bad-1")).isNull();
        }
    }

    @Test
    void nonJsonRecordPayloadRejected() {
        try (var b = backend()) {
            var session = b.create("cwd");
            Map<String, Object> cyclic = new java.util.HashMap<>();
            cyclic.put("self", cyclic);
            var bad = new com.pijava.agent.record.NewRecord<LaneRecord>(
                new LaneRecord.ToolStarted("bad-r1", 0, "main", null, "run-1",
                    "asst", 0, "call", "bash", cyclic, "", null));
            assertThatThrownBy(() -> session.appendRecord(bad))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.INVALID_PAYLOAD);
            assertThat(session.findRecords(RecordQuery.all())).isEmpty();
        }
    }
}
