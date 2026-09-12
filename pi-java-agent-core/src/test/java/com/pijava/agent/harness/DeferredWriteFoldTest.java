package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/22 D3: the folded {@code pendingWrites} set — writes the operation
 * accepted but whose target entry is not in the recovery slice — and the two
 * corruption rules that guard a deferred write's target
 * (pi {@code reducer.ts:543-558, 383-385}).
 *
 * <p>Split out of {@link LaneStateFoldTest} to keep both files under the
 * 500-line limit; the scaffolding is the usual per-file harness, as in the
 * sibling fold tests.</p>
 */
class DeferredWriteFoldTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static final AssistantMessage DONE = AssistantMessage.empty()
        .withContent(List.of(new ContentBlock.TextContent("done")))
        .withStopReason("stop");

    // ── Harness scaffolding ─────────────────────────────────

    private static StreamFn simpleStreamFn() {
        return (messages, model, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "done", DONE),
            new StreamEvent.StreamDone("stop", null, DONE)));
    }

    /** Every call fails: the run terminates with a trailing errored entry. */
    private static StreamFn errorStreamFn() {
        var error = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("boom")))
            .withStopReason("error");
        return (messages, model, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "boom", error),
            new StreamEvent.StreamDone("error", null, error)));
    }

    private static StreamFn toolUseThenStopStreamFn(String toolName) {
        var toolUse = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(
                "call-1", toolName, Map.of("text", "hello"))))
            .withStopReason("tool_use");
        var calls = new AtomicInteger();
        return (messages, model, options) -> {
            var partial = calls.incrementAndGet() == 1 ? toolUse : DONE;
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "x", partial),
                new StreamEvent.StreamDone(partial.stopReason(), null, partial)));
        };
    }

    private static AgentTool<String, Void> echoTool() {
        return new AgentTool<>() {
            @Override public String name() { return "echo"; }
            @Override public String label() { return "echo"; }
            @Override public String description() { return "Echo input"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success(params);
            }
        };
    }

    private static AgentHarness harness(StreamFn sf, ToolRegistry registry) {
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, registry, null, null,
            DriveMode.MANUAL, null, Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    private static void drive(AgentHarness h, String lane) {
        var action = h.peekAction(lane);
        while (action != null) {
            action = h.executeAction(lane, action);
        }
    }

    /** Fold the live lane's log exactly as resume recovery would. */
    private static LaneStateFolder.FoldedState foldOf(AgentHarness h, String lane) {
        var snapshot = h.snapshot(lane);
        return LaneStateFolder.fold(lane, snapshot.records(), snapshot.transcript(),
            snapshot.transcript().stream().filter(Entry::isConfiguration).toList());
    }

    // ── Deferred writes (docs/22 D3) ────────────────────────

    private static Entry userMessage(String id, String text) {
        return new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    @Test
    void foldPendingWritesIsEmptyOnALiveLane() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        // 在**未发生 compaction、未发生 retry 丢弃**的 lane 上，每个写入点都是
        // `lane.transcript.add(e)` 紧跟 `lane.pendingWrites.add(e)`，所以
        // WriteDeferred 的 target 都还在 transcript 里 ⇒ fold 视为「已应用」。
        // （该全称断言在 compaction/retry 之后不成立，见下面两个用例。）
        // live 的 pendingWrites 是「尚未持久化」的流动标记，与 fold 的「已接受未应用」
        // 不是同一个集合，因此不可拿两者比大小。
        assertThat(foldOf(h, "default").pendingWrites()).isEmpty();
    }

    @Test
    void foldPendingWritesIsEmptyAfterAnIdleCompaction() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harness(toolUseThenStopStreamFn("echo"), registry);
        h.run("default", "go");
        drive(h, "default");
        h.compact("default", com.pijava.agent.compaction.CompactionSettings.defaults());

        // compaction 确实替换了 transcript（CompactionExecutor:86-87），被丢弃条目的
        // write_deferred 记录仍在日志里 —— 前置条件用断言钉住，避免本用例变成空绿。
        var transcriptIds = h.snapshot("default").transcript().stream()
            .map(Entry::id).toList();
        var writtenIds = h.snapshot("default").records().stream()
            .filter(LaneRecord.WriteDeferred.class::isInstance)
            .map(record -> ((LaneRecord.WriteDeferred) record).target().entry().id())
            .toList();
        assertThat(writtenIds).isNotEmpty();
        assertThat(writtenIds).anyMatch(id -> !transcriptIds.contains(id));

        // 但空闲 compaction 会**新开一个 operation**（started/step/finished 三连），
        // 它成为最后锚点，于是那些陈旧写入落在 operation-scoped 切片之外 ⇒ fold
        // 不再报待应用。作用域收窄顺带掩盖了这个泄漏；真正暴露它的是不新开
        // operation 的丢弃路径（下一个用例）。
        var folded = foldOf(h, "default");

        assertThat(folded.idle()).isTrue();
        assertThat(folded.pendingWrites()).isEmpty();
    }

    @Test
    void foldPendingWritesLeaksWritesDroppedByRetry() {
        var h = harness(errorStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        var droppedId = h.snapshot("default").transcript().get(1).id();
        h.dropTrailingErrorAssistant("default");

        // 已知泄漏（本用例将其钉住，而非修复）：dropTrailingErrorAssistant
        // （AgentHarness:460）移除尾部 assistant 条目，却不发任何抵消记录，
        // 也不新开 operation —— 于是该条目的 write_deferred 仍落在 operation
        // 作用域内、target 却已不在 entries 里 ⇒ fold 报「已接受未应用」。
        var pending = foldOf(h, "default").pendingWrites();

        assertThat(pending).isNotEmpty();
        assertThat(pending).extracting(entry -> entry.entry().id()).containsExactly(droppedId);
    }

    @Test
    void foldPendingWritesSurfacesWritesWhoseTargetNeverLanded() {
        // 崩溃场景：write_deferred 记录已落库，target entry 没落库 —— 恢复时必须视为待应用。
        // 这才是本派生的唯一真实消费者。
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(userMessage("never-persisted", "lost")));

        var folded = LaneStateFolder.fold("default", List.of(write), List.of(), List.of());

        assertThat(folded.pendingWrites()).hasSize(1);
        assertThat(folded.pendingWrites().get(0).entry().id()).isEqualTo("never-persisted");
    }

    @Test
    void foldKeepsPendingWritesWhenTheOperationAborted() {
        // 与 steer/followUp 不同：延迟写入在 abort 后仍保留（pi reducer.ts:543-558）。
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(userMessage("never-persisted", "lost")));
        var records = List.<LaneRecord>of(
            new LaneRecord.OperationStarted("run-1", 0, "default", null, null,
                new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null)),
            new LaneRecord.AbortRequested("a-1", 0, "default", null, "run-1"),
            write,
            new LaneRecord.OperationFinished("f-1", 0, "default", null, "run-1",
                OperationOutcome.ABORTED, null, null));

        assertThat(LaneStateFolder.fold("default", records, List.of(), List.of()).pendingWrites())
            .hasSize(1);
    }

    @Test
    void foldRejectsDeferredAssistantEntryWithoutHandle() {
        var entry = new Entry.Message("a-1", 0, null, null,
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("")), "deferred", null), null);
        assertThatThrownBy(() -> LaneStateFolder.fold("default", List.of(), List.of(entry), List.of()))
            .isInstanceOf(RecordLogCorruption.class)
            .hasMessageContaining("invalid_deferred_handle");
    }

    @Test
    void foldRejectsWriteDeferredTargetThatContradictsAnExistingEntry() {
        var existing = new Entry.Message("t-1", 1, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("real"))), null);
        var contradicting = new ProvisionedEntry<>(new Entry.Message("t-1", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("other"))), null));
        var record = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "", contradicting);

        assertThatThrownBy(() -> LaneStateFolder.fold("default", List.of(record),
            List.of(existing), List.of()))
            .isInstanceOf(RecordLogCorruption.class)
            .hasMessageContaining("provisioned_entry_mismatch");
    }
}
