package com.pijava.agent.session;

import static com.pijava.agent.session.ConformanceSupport.assistantMessage;
import static com.pijava.agent.session.ConformanceSupport.custom;
import static com.pijava.agent.session.ConformanceSupport.operationStarted;
import static com.pijava.agent.session.ConformanceSupport.stepAttempt;
import static com.pijava.agent.session.ConformanceSupport.userMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.agent.entry.Entry;

import org.junit.jupiter.api.Test;

/**
 * Conformance group 5 — repository and forks (6 cases).
 */
public abstract class ConformanceGroup3Test {

    protected abstract ConformanceBackend backend();

    @Test
    void createListOpenAndDuplicateCreate() {
        try (var b = backend()) {
            var session = b.create("cwd", "fixed-id");
            assertThat(b.list()).extracting(SessionMetadata::id).contains("fixed-id");
            var reopened = b.open("fixed-id");
            assertThat(reopened.getMetadata().id()).isEqualTo("fixed-id");
            assertThatThrownBy(() -> b.create("cwd", "fixed-id"))
                .isInstanceOf(SessionError.class);
        }
    }

    @Test
    void deleteIsIdempotentAndOpenAfterDeleteThrows() {
        try (var b = backend()) {
            var session = b.create("cwd");
            String id = session.getMetadata().id();
            b.delete(id);
            b.delete(id); // idempotent
            assertThatThrownBy(() -> b.open(id))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.NOT_FOUND);
        }
    }

    @Test
    void branchScopeForkCopiesPathAndFiltersLabels() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "root"), "main");
            session.appendEntry(assistantMessage("m2", "answer"), "main");
            session.appendEntry(userMessage("m3", "side"), "main");
            session.appendEntry(custom("c1", "event", null), "main");
            session.setName("original");
            session.setLabel("m2", "kept");
            session.setLabel("m3", "not-copied");
            session.appendRecord(stepAttempt("r1", "main", "run-1"));

            var forked = b.fork(session.getMetadata().id(), new ForkOptions.Branch("m3", null));
            var entries = forked.findEntries(EntryQuery.all());
            // Branch fork at "before m3": copies m1 + m2.
            assertThat(entries).extracting(Entry::id).containsExactlyInAnyOrder("m1", "m2");
            // Records are not copied.
            assertThat(forked.findRecords(RecordQuery.all())).isEmpty();
            // Name is copied; labels scoped to copied entries.
            assertThat(forked.getName()).isEqualTo("original");
            assertThat(forked.getLabel("m2")).isEqualTo("kept");
            assertThat(forked.getLabel("m3")).isNull();
            // Stats recomputed from copied messages.
            assertThat(forked.getStats().messageCount()).isEqualTo(2);
        }
    }

    @Test
    void treeScopeForkCopiesEverything() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "root"), "main");
            session.createLane("feature", "m1");
            session.appendEntry(assistantMessage("m2", "on feature"), "feature");
            session.setName("tree-name");
            session.setLabel("m1", "root-label");

            var forked = b.fork(session.getMetadata().id(), new ForkOptions.Tree());
            assertThat(forked.findEntries(EntryQuery.all()))
                .extracting(Entry::id).containsExactlyInAnyOrder("m1", "m2");
            assertThat(forked.getLanes()).extracting(LanePointer::lane)
                .containsExactlyInAnyOrder("main", "feature");
            assertThat(forked.getName()).isEqualTo("tree-name");
            assertThat(forked.getLabel("m1")).isEqualTo("root-label");
            assertThat(forked.getStats().messageCount()).isEqualTo(2);
        }
    }

    @Test
    void labelTimestampTracksLatestClearsAndSurvivesFork() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "root"), "main");
            session.appendEntry(assistantMessage("m2", "answer"), "main");
            // 未打标 ⇒ 无时间戳（pi labelTimestampsById 无条目）。
            assertThat(session.getLabelTimestamp("m2")).isNull();
            session.setLabel("m2", "kept");
            var t1 = session.getLabelTimestamp("m2");
            assertThat(t1).isNotNull();
            session.setLabel("m2", "renamed");
            var t2 = session.getLabelTimestamp("m2");
            // 最新一条赢（pi _buildIndex :1103-1122 同语义）。
            assertThat(t2).isNotNull().isAfterOrEqualTo(t1);
            // 分支复制重发保留原 label 时间戳（pi createBranchedSession:1694-1712）。
            // 对比基准走同一持久化往返（fork 读的是落盘态；jsonl 行精度到毫秒，
            // 活会话内存里是纳秒——直接比 live 值会差一个量化步）。
            var forked = b.fork(session.getMetadata().id(), new ForkOptions.Tree());
            var reopened = b.open(session.getMetadata().id());
            assertThat(forked.getLabel("m2")).isEqualTo("renamed");
            assertThat(forked.getLabelTimestamp("m2"))
                .isEqualTo(reopened.getLabelTimestamp("m2"));
            // 清除 ⇒ 值与时间戳同删（pi :996-997 否则臂两表同删）。
            session.setLabel("m2", null);
            assertThat(session.getLabel("m2")).isNull();
            assertThat(session.getLabelTimestamp("m2")).isNull();
        }
    }

    @Test
    void forkPositionsAtAndBeforeAndInvalidTarget() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(userMessage("m1", "one"), "main");
            session.appendEntry(assistantMessage("m2", "two"), "main");
            session.appendEntry(userMessage("m3", "three"), "main");

            var atFork = b.fork(session.getMetadata().id(),
                new ForkOptions.Branch("m2", ForkOptions.Branch.Position.AT));
            assertThat(atFork.findEntries(EntryQuery.all()))
                .extracting(Entry::id).containsExactlyInAnyOrder("m1", "m2");

            var beforeFork = b.fork(session.getMetadata().id(),
                new ForkOptions.Branch("m3", ForkOptions.Branch.Position.BEFORE));
            assertThat(beforeFork.findEntries(EntryQuery.all()))
                .extracting(Entry::id).containsExactlyInAnyOrder("m1", "m2");

            // Default target: fork at the main leaf (a message entry).
            var defaultFork = b.fork(session.getMetadata().id(), new ForkOptions.Branch(null, null));
            assertThat(defaultFork.findEntries(EntryQuery.all()))
                .extracting(Entry::id).containsExactlyInAnyOrder("m1", "m2", "m3");
        }
    }

    @Test
    void nonMessageLeafAsDefaultForkTargetIsInvalid() {
        try (var b = backend()) {
            var session = b.create("cwd");
            session.appendEntry(custom("c1", "event", null), "main");
            assertThatThrownBy(() -> b.fork(session.getMetadata().id(),
                new ForkOptions.Branch(null, null)))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.INVALID_FORK_TARGET);
        }
    }
}
