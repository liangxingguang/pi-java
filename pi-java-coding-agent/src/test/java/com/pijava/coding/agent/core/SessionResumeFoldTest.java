package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.RecordQuery;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resume behavior after the record-log fold was retired (docs/30).
 *
 * <p>The log is loaded but no longer folded into orchestration state, so the
 * lane comes back idle with empty queues; the transcript seed stays responsible
 * for display context. Records are lane-scoped, so these seed under the
 * harness's lane name.</p>
 */
class SessionResumeFoldTest {

    private static final String LANE = AgentHarness.DEFAULT_LANE;

    /**
     * The cwd the seeded session is created under.
     *
     * <p>⚠️ **docs/39**：必须与 resume 路径查找的 cwd 相同。会话按项目目录存放
     * （pi {@code getDefaultSessionDir(cwd)}），而 {@code resolvePersistentWeb}
     * 读的就是 {@code user.dir}；夹具原先写死字面量 {@code "cwd"}，只有在
     * 「跨全部项目取最新」那种越界扫描下才碰巧能被找到。</p>
     */
    private static final String CWD = System.getProperty("user.dir");

    private static ProvisionedEntry<Entry.Message> userMessage(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    private static LaneRecord.OperationStarted openRun(String id) {
        return new LaneRecord.OperationStarted(id, 0, LANE, null, null,
            new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null));
    }

    private static LaneRecord.OperationFinished finish(String id, OperationOutcome outcome) {
        return new LaneRecord.OperationFinished("fin-" + id, 0, LANE, null, id,
            outcome, null, 10L);
    }

    private static ProvisionedEntry<Entry.Message> queuedTarget(String queueSeq, String text) {
        return new ProvisionedEntry<>(new Entry.Message(queueSeq, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    /** Persist a session whose lane carries the given records. */
    private static Path seed(RecordSeed seed) throws Exception {
        Path root = Files.createTempDirectory("pi-resume-fold");
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var session = handle.create(CWD, null);
            session.createLane(LANE, null);
            seed.run(session);
        } finally {
            handle.close();
        }
        return root;
    }

    private interface RecordSeed {
        void run(com.pijava.agent.session.Session<?> session);
    }

    /**
     * Resume into a session backed by a persistent repository at {@code root}.
     *
     * <p>⚠️ **台账 A13：这里必须注入 {@link FauxProvider}。** 此前用
     * {@code AgentSession.createWeb(args)}（无参版）⇒ 走 {@code DefaultProviders}
     * 的**真实 provider（网络）**，而下面那条用例的断言隐含「首次调用必成功」；
     * 全树负载下真调用变慢会触发引擎 post-run 的**重试环**，后者开一条**续跑
     * operation**，于是「恰 2 条 `OperationStarted`」变成 3 条、且墙钟被退避拉到
     * 300+ 秒。夹具不该依赖网络。</p>
     */
    private static AgentSession resume(Path root) {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        return AgentSession.createWeb(ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello",
            "--session-dir", root.toString()}),
            providers,
            new ToolContext(System.getProperty("user.dir"), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    /**
     * Queues are <b>not</b> rebuilt from the record log (docs/30 §4.3).
     *
     * <p>This test used to assert the opposite — that an enqueue which was never
     * consumed or cancelled came back as pending. That behavior is now
     * deliberately dropped: pi keeps queues in-process, so a crash loses them,
     * and replaying an old enqueue would re-inject a half-finished prompt into
     * an unrelated later run. The records themselves are still loaded (and
     * marked persisted, so write-through stays a no-op) — they are simply no
     * longer a source of orchestration state.</p>
     */
    @Test
    void attachLoadsRecordsWithoutRebuildingQueuesFromThem() throws Exception {
        Path root = seed(session -> {
            session.appendEntry(userMessage("m1", "hello"), LANE);
            session.appendRecord(new NewRecord<>(openRun("run-1")));
            session.appendRecord(new NewRecord<>(finish("run-1", OperationOutcome.COMPLETED)));
            session.appendRecord(new NewRecord<>(new LaneRecord.QueueEnqueued(
                "q1", 0, LANE, null, QueueKind.FOLLOW_UP, null,
                queuedTarget("0", "queued follow-up"))));
        });

        var resumed = resume(root);
        try {
            var snapshot = resumed.harness().snapshot(LANE);
            // The finished operation leaves the lane idle …
            assertThat(snapshot.operation()).isNull();
            // … but the unconsumed enqueue does not come back.
            assertThat(snapshot.queues().followUp()).isEmpty();
            // The log itself is loaded, and marked persisted so write-through
            // does not append it a second time.
            assertThat(resumed.persistedRecordIds()).contains("run-1", "fin-run-1", "q1");
        } finally {
            resumed.close();
        }
    }

    /**
     * A session persisted mid-run keeps an open operation. Resuming must settle
     * it at the resume boundary (docs/30 §4.2) so the lane comes back idle and a
     * new run may open its own operation — storage rejects a second open
     * operation on the same lane (docs/21 F6).
     *
     * <p>Until this settlement existed, a crashed session resumed to the
     * checkpoint phase and relied on the drive loop's {@code TryFinishRun} to
     * close the operation. That action lives only in the retired step chain,
     * and {@code PiLaneEngine.run} refuses a non-idle lane — so a resumed
     * session threw {@code IllegalStateException: Cannot start run} on its
     * first prompt.</p>
     *
     * <p>The idle check below is the very predicate that guard rejects, and
     * "no open operation in storage" is the one storage rejects, so
     * drivability is pinned without needing a provider to stream from.</p>
     */
    @Test
    void resumeSettlesACrashOpenedOperationSoANewRunCanOpen() throws Exception {
        Path root = seed(session -> {
            session.appendEntry(userMessage("m1", "mid-run prompt"), LANE);
            session.appendRecord(new NewRecord<>(openRun("run-crashed")));
        });

        var resumed = resume(root);
        try {
            // Idle, not checkpoint: the crash-opened operation was closed.
            assertThat(resumed.harness().snapshot(LANE).operation()).isNull();

            // A fresh run may now open its own operation. `prompt` is blocking,
            // so it runs the whole turn: the new operation is opened and closed
            // by its own finish, and the flush on close writes both records.
            resumed.harness().prompt(LANE, "next prompt", List.of());
        } finally {
            resumed.close();
        }

        // Nothing left open — that is exactly the predicate storage rejects.
        assertThat(openOperations(root)).isEmpty();

        var started = recordsFrom(root).stream()
            .filter(LaneRecord.OperationStarted.class::isInstance)
            .map(LaneRecord.OperationStarted.class::cast)
            .toList();
        assertThat(started).hasSize(2);            // the crashed one + this run
        assertThat(started.get(1).id()).isNotEqualTo("run-crashed");

        var settlement = recordsFrom(root).stream()
            .filter(LaneRecord.OperationFinished.class::isInstance)
            .map(LaneRecord.OperationFinished.class::cast)
            .toList();
        var crash = settlement.stream()
            .filter(r -> "run-crashed".equals(r.runId())).findFirst().orElseThrow();
        // A process dying is a stop, not a failure: FAILED would fault the lane.
        assertThat(crash.outcome()).isEqualTo(OperationOutcome.ABORTED);
        // Every opened operation is closed by the run that opened it.
        assertThat(settlement.stream().map(LaneRecord.OperationFinished::runId).toList())
            .containsExactlyInAnyOrderElementsOf(
                started.stream().map(LaneRecord.OperationStarted::id).toList());
    }

    /** Re-open the persisted session to inspect what was actually written. */
    private static List<LaneRecord.OperationStarted> openOperations(Path root) {
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var meta = handle.latest(CWD).orElseThrow();
            return handle.open(meta).findOpenOperations(LANE, 10);
        } finally {
            handle.close();
        }
    }

    /** Every record on the lane, read back from disk. */
    private static List<LaneRecord> recordsFrom(Path root) {
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var meta = handle.latest(CWD).orElseThrow();
            return handle.open(meta).findRecords(new RecordQuery(
                LANE, null, null, null, null, EntryOrder.OLDEST_FIRST, null));
        } finally {
            handle.close();
        }
    }

    /** Guards the test's own assumption that records survive a round trip. */
    @Test
    void seededRecordsAreReadable() throws Exception {
        Path root = seed(session -> {
            session.appendRecord(new NewRecord<>(openRun("run-1")));
            session.appendRecord(new NewRecord<>(finish("run-1", OperationOutcome.COMPLETED)));
        });

        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var meta = handle.latest(CWD).orElseThrow();
            var records = handle.open(meta).findRecords(new RecordQuery(
                LANE, null, null, null, null, EntryOrder.OLDEST_FIRST, null));
            assertThat(records).hasSize(2);
            assertThat(records.get(1)).isInstanceOf(LaneRecord.OperationFinished.class);
            assertThat(handle.open(meta).findEntries(new EntryQuery(
                null, null, EntryOrder.OLDEST_FIRST, null, null))).isEmpty();
        } finally {
            handle.close();
        }
    }
}
