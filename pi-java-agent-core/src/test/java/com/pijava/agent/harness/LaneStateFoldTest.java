package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.record.StepKind;
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
 * Sentinel for {@link LaneStateFolder}: the folded record log must agree with
 * the live lane it was folded from (docs/21 §5), plus the record-log
 * corruption rules of {@link LaneStateFolder#validateRecordLog}.
 */
class LaneStateFoldTest {

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

    // ── Sentinel: fold == live ──────────────────────────────

    @Test
    void foldMatchesLiveLaneAfterCompletedRun() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        var folded = foldOf(h, "default");
        var live = h.snapshot("default");

        assertThat(folded.idle()).isTrue();
        assertThat(live.operation()).isNull();
        assertThat(folded.runId()).isNull();
        assertThat(folded.faulted()).isFalse();
        assertThat(folded.aborted()).isFalse();
        assertThat(folded.pendingSteer()).isEmpty();
        assertThat(folded.pendingFollowUp()).isEmpty();
        assertThat(folded.pendingNextRun()).isEmpty();
        assertThat(folded.newestOwn()).isNotNull();
        assertThat(folded.newestOwn().role()).isEqualTo("assistant");
        assertThat(folded.newestOwn().stopReason())
            .isEqualTo(h.lastAssistantMessage().stopReason());
    }

    /** While idle there is no open operation, so no step count either. */
    @Test
    void foldReportsNoStepIndexWhenIdle() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        assertThat(foldOf(h, "default").stepIndex()).isZero();
    }

    @Test
    void foldMatchesLiveLaneMidRun() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harness(toolUseThenStopStreamFn("echo"), registry);

        // Stop right after the tool executes: the operation is still open.
        var action = h.run("default", "go");
        while (action != null && !(action instanceof Action.ExecuteTool)) {
            action = h.executeAction("default", action);
        }
        action = h.executeAction("default", action);

        var folded = foldOf(h, "default");
        var live = h.snapshot("default");

        assertThat(live.operation()).isNotNull();
        // fold normalizes the open run to CHECKPOINT, so it is never idle here.
        assertThat(folded.idle()).isFalse();
        assertThat(folded.runId()).isEqualTo(live.operation().id());
        assertThat(folded.stepIndex()).isEqualTo(1);
    }

    @Test
    void foldPendingQueuesEqualLiveQueues() {
        var h = harness(simpleStreamFn(), null);
        // Enqueued while idle and never drained: the fold must still see them.
        h.followUp("default", "follow me");
        h.nextRun("default", "next");
        h.steer("default", "steer me");

        var folded = foldOf(h, "default");
        var live = h.snapshot("default").queues();

        assertThat(folded.pendingFollowUp()).isEqualTo(live.followUp());
        assertThat(folded.pendingNextRun()).isEqualTo(live.nextRun());
        assertThat(folded.pendingSteer()).isEqualTo(live.steer());
        assertThat(folded.pendingFollowUp().get(0).prompt()).isEqualTo("follow me");
    }

    @Test
    void foldSubtractsConsumedAndCancelledItems() {
        var h = harness(simpleStreamFn(), null);
        h.followUp("default", "consumed");
        drive(h, "default");
        h.followUp("default", "cancelled");
        h.cancelQueued("default", "followUp");

        var folded = foldOf(h, "default");

        assertThat(folded.pendingFollowUp()).isEmpty();
        assertThat(h.snapshot("default").queues().followUp()).isEmpty();
    }

    @Test
    void foldDerivesAbortedOutcomeOnlyAfterTheRunFinishes() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harness(toolUseThenStopStreamFn("echo"), registry);

        var action = h.run("default", "go");
        while (action != null && !(action instanceof Action.ExecuteTool)) {
            action = h.executeAction("default", action);
        }
        action = h.executeAction("default", action);

        // Aborting without driving the run to its finish leaves the operation
        // open, so the fold still reports an open operation, not an abort.
        h.abort("default");
        var openFold = foldOf(h, "default");
        assertThat(openFold.idle()).isFalse();
        assertThat(openFold.aborted()).isFalse();

        while (action != null) {
            action = h.executeAction("default", action);
        }

        var finished = h.snapshot("default").records().stream()
            .filter(r -> r instanceof LaneRecord.OperationFinished)
            .map(r -> (LaneRecord.OperationFinished) r)
            .toList();
        assertThat(finished).hasSize(1);
        assertThat(finished.get(0).outcome()).isEqualTo(OperationOutcome.ABORTED);

        var folded = foldOf(h, "default");
        assertThat(folded.idle()).isTrue();
        assertThat(folded.aborted()).isTrue();
        assertThat(folded.faulted()).isFalse();
    }

    @Test
    void foldDerivesEffectiveConfiguration() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        // Only entries that are actually emitted can be derived; a plain run
        // records no configuration change, so every field stays null.
        var folded = foldOf(h, "default");
        assertThat(folded.effectiveConfiguration().model()).isNull();
        assertThat(folded.effectiveConfiguration().activeToolNames()).isNull();
    }

    @Test
    void foldReadsConfigurationEntriesInOrder() {
        var entries = List.<Entry>of(
            new Entry.ModelChange("c1", 1, null, Instant.now(), "anthropic", "sonnet"),
            new Entry.ThinkingLevelChange("c2", 2, null, Instant.now(), "high"),
            new Entry.ActiveToolsChange("c3", 3, null, Instant.now(), List.of("bash")),
            new Entry.ModelChange("c4", 4, null, Instant.now(), "openai", "gpt"));

        var folded = LaneStateFolder.fold("default", List.of(), List.of(), entries);

        assertThat(folded.effectiveConfiguration().model())
            .isEqualTo(ModelId.of("openai", "gpt"));
        assertThat(folded.effectiveConfiguration().thinkingLevel()).isEqualTo("high");
        assertThat(folded.effectiveConfiguration().activeToolNames()).containsExactly("bash");
    }

    @Test
    void foldIdleCompactionEmitsStartedStepAndFinished() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");
        h.compact("default", com.pijava.agent.compaction.CompactionSettings.defaults());

        var steps = h.snapshot("default").records().stream()
            .filter(r -> r instanceof LaneRecord.StepAttempt s
                && s.step() == StepKind.COMPACTION)
            .map(r -> (LaneRecord.StepAttempt) r)
            .toList();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).compactionReason()).isEqualTo("manual");

        // The idle compaction opens and closes its own operation, so the lane
        // folds back to idle rather than picking up a stray open operation.
        var folded = foldOf(h, "default");
        assertThat(folded.idle()).isTrue();
    }

    // ── Deferred writes (docs/23 D3) ────────────────────────

    private static Entry userMessage(String id, String text) {
        return new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    @Test
    void foldPendingWritesIsEmptyOnALiveLane() {
        var h = harness(simpleStreamFn(), null);
        h.run("default", "hello");
        drive(h, "default");

        // 有意的退化解：每个写入点都是 `lane.transcript.add(e)` 紧跟 `lane.pendingWrites.add(e)`，
        // 所以 WriteDeferred 的 target 必然已在 ownEntries 里 ⇒ fold 视为「已应用」。
        // live 的 pendingWrites 是「尚未持久化」的流动标记，与 fold 的「已接受未应用」
        // 不是同一个集合，因此不可拿两者比大小。
        assertThat(foldOf(h, "default").pendingWrites()).isEmpty();
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

    // ── validateRecordLog ───────────────────────────────────

    private static LaneRecord.OperationStarted op(String id) {
        return new LaneRecord.OperationStarted(id, 0, "default", null, null,
            new LaneRecord.OperationStarted.Compaction(null, ""));
    }

    private static LaneRecord.OperationFinished fin(String runId) {
        return new LaneRecord.OperationFinished("f-" + runId, 0, "default", null, runId,
            OperationOutcome.COMPLETED, null, null);
    }

    private static LaneRecord.StepAttempt step(String runId, StepKind kind, int attempt,
                                                String reason) {
        return new LaneRecord.StepAttempt("s-" + runId + attempt, 0, "default", null, runId,
            kind, attempt, "e1", reason, null, null, null, null, null);
    }

    private static void assertCorrupt(List<LaneRecord> records, String code) {
        assertThatThrownBy(() -> LaneStateFolder.validateRecordLog("default", records))
            .isInstanceOf(RecordLogCorruption.class)
            .hasMessageContaining(code);
    }

    @Test
    void rejectsMultipleOpenOperations() {
        assertCorrupt(List.of(op("r1"), op("r2")), "multiple_open_operations");
    }

    @Test
    void rejectsRecordForUnknownOperation() {
        assertCorrupt(List.of(op("r1"), step("ghost", StepKind.ASSISTANT, 0, null)),
            "unknown_operation");
    }

    @Test
    void rejectsRecordAfterOperationFinish() {
        assertCorrupt(List.of(op("r1"), fin("r1"), step("r1", StepKind.ASSISTANT, 0, null)),
            "record_after_finish");
    }

    @Test
    void rejectsNonConsecutiveAttempt() {
        assertCorrupt(List.of(op("r1"), step("r1", StepKind.ASSISTANT, 0, null),
            step("r1", StepKind.ASSISTANT, 2, null)), "non_consecutive_attempt");
    }

    @Test
    void rejectsCompactionStepWithoutReason() {
        assertCorrupt(List.of(op("r1"), step("r1", StepKind.COMPACTION, 0, null)),
            "invalid_compaction_reason");
    }

    @Test
    void rejectsCompactionReasonOnAnAssistantStep() {
        assertCorrupt(List.of(op("r1"), step("r1", StepKind.ASSISTANT, 0, "manual")),
            "invalid_compaction_reason");
    }

    @Test
    void rejectsQueueEnqueueAfterAbort() {
        var enqueue = new LaneRecord.QueueEnqueued("q1", 0, "default", null,
            QueueKind.STEER, "r1", HarnessUtils.provisionedQueueTarget(
                new LaneInfo.QueuedItem("late", 0)));
        assertCorrupt(List.of(op("r1"),
            new LaneRecord.AbortRequested("a1", 0, "default", null, "r1"), enqueue),
            "queue_after_abort");
    }

    @Test
    void rejectsCancellationWithoutMatchingEnqueue() {
        var cancel = new LaneRecord.QueueCancelled("c1", 0, "default", null, null, "0");
        assertCorrupt(List.of(cancel), "invalid_queue_cancellation");
    }

    @Test
    void acceptsAWellFormedLog() {
        var enqueue = new LaneRecord.QueueEnqueued("q1", 0, "default", null,
            QueueKind.FOLLOW_UP, null, HarnessUtils.provisionedQueueTarget(
                new LaneInfo.QueuedItem("hi", 0)));
        var cancel = new LaneRecord.QueueCancelled("c1", 0, "default", null, null, "0");
        var records = new ArrayList<LaneRecord>();
        records.add(op("r1"));
        records.add(step("r1", StepKind.ASSISTANT, 0, null));
        records.add(fin("r1"));
        records.add(enqueue);
        records.add(cancel);

        LaneStateFolder.validateRecordLog("default", records);

        assertThat(LaneStateFolder.fold("default", records, List.of(), List.of()).idle())
            .isTrue();
    }
}
