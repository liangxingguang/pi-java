package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.BeforeToolResult;
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

/**
 * Agent-loop L2 防漂移回归（docs/20 §4）:
 * ⑥ peekAction 出口不变量断言（{@link LoopInvariants} + abort 护栏）;
 * ⑦ golden-trace 测试 — 断言状态机产出的 {@link Action} 序列本身，任何人改
 *    peekAction 的分支顺序，序列立刻变化、测试立刻红。
 */
class AgentLoopL2Test {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** A tool that records how many times it actually executed. */
    private static AgentTool<String, Void> recordingTool(String name, Map<String, Object> schema,
                                                         AtomicInteger executed) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "Test tool " + name; }
            @Override public Map<String, Object> inputSchema() { return schema; }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                executed.incrementAndGet();
                return ToolResult.success(params);
            }
        };
    }

    private static StreamFn scriptedStreamFn(List<AssistantMessage> partials) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            var partial = partials.get(Math.min(index.incrementAndGet() - 1,
                partials.size() - 1));
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "x", partial),
                new StreamEvent.StreamDone(partial.stopReason(), null, partial)));
        };
    }

    private static AgentHarness harness(ToolRegistry registry, StreamFn streamFn) {
        return AgentHarness.create(new HarnessConfig(
                streamFn, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, registry, null, null,
                DriveMode.MANUAL, null, Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
                com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    private static AssistantMessage toolUsePartial(String stopReason, String toolName,
                                                   Map<String, Object> args) {
        return AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent("call-1", toolName, args)))
            .withStopReason(stopReason);
    }

    private static AssistantMessage stopPartial() {
        return AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
    }

    /**
     * Drive a run to completion, collecting every action the state machine
     * produces (including the final {@code null}-terminating execute result
     * is NOT collected — only actions returned by the harness).
     */
    private static List<Action> driveCollectingActions(AgentHarness h, String prompt) {
        var actions = new ArrayList<Action>();
        h.run("default", prompt);
        var action = h.peekAction("default");
        while (action != null) {
            actions.add(action);
            action = h.executeAction("default", action);
        }
        return List.copyOf(actions);
    }

    /** Compact descriptor for an action, stable against entry-id UUIDs. */
    private static String describe(Action a) {
        return switch (a) {
            case Action.ApplyPendingWrite apw -> "ApplyPendingWrite(" + apw.entryType() + ")";
            case Action.StreamAssistant sa -> "StreamAssistant(" + sa.step() + "," + sa.attempt() + ")";
            case Action.TryFinishRun tfr -> "TryFinishRun(" + tfr.outcome() + ")";
            case Action.ExecuteTool et -> "ExecuteTool(" + et.toolName() + ")";
            case Action.ExecuteToolBatch etb -> "ExecuteToolBatch(" + etb.calls().size() + ")";
            case Action.ConsumeQueueItem cqi -> "ConsumeQueueItem(" + cqi.queue() + ")";
            case Action.FinishOperation fo -> "FinishOperation(" + fo.outcome() + ")";
        };
    }

    private static List<String> labels(List<Action> actions) {
        return actions.stream().map(AgentLoopL2Test::describe).toList();
    }

    // ── ⑦ golden-trace: 断言 Action 序列本身 ────────────────

    @Test
    void toolUseThenFollowUpDrivesTheExactActionSequence() {
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("read", Map.of(), new AtomicInteger()));
        var h = harness(registry, scriptedStreamFn(List.of(
            toolUsePartial("tool_use", "read", Map.of("path", "a.txt")), stopPartial())));

        var actions = driveCollectingActions(h, "read a.txt");

        // Any reordering of peekAction branches changes this sequence.
        assertThat(labels(actions)).containsExactly(
            "ApplyPendingWrite(message)", "StreamAssistant(assistant,0)", "ApplyPendingWrite(message)",
            "TryFinishRun(tool_use)", "ExecuteTool(read)", "ApplyPendingWrite(message)",
            "StreamAssistant(assistant,0)", "ApplyPendingWrite(message)", "TryFinishRun(completed)",
            "FinishOperation(completed)");
        // The tool payload survives the state machine unchanged.
        assertThat(actions.get(4))
            .isEqualTo(new Action.ExecuteTool("call-1", "read", Map.of("path", "a.txt")));
    }

    @Test
    void lengthStopTruncationDrivesTheRetrySequence() {
        var executed = new AtomicInteger();
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", Map.of(), executed));
        var h = harness(registry, scriptedStreamFn(List.of(
            toolUsePartial("length", "echo", Map.of("text", "hello")), stopPartial())));

        var actions = driveCollectingActions(h, "truncated call");

        // L1-③ as a sequence: the truncated call is failed back (TryFinishRun
        // with "length" → one failed tool entry) before the model retries.
        assertThat(labels(actions)).containsExactly(
            "ApplyPendingWrite(message)", "StreamAssistant(assistant,0)", "ApplyPendingWrite(message)",
            "TryFinishRun(length)", "ApplyPendingWrite(message)", "StreamAssistant(assistant,0)",
            "ApplyPendingWrite(message)", "TryFinishRun(completed)", "FinishOperation(completed)");
        assertThat(executed.get()).isZero();
    }

    @Test
    void denyAndTerminateCutsTheSequenceAtTheDeniedTool() {
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", Map.of(), new AtomicInteger()));
        var h = harness(registry, scriptedStreamFn(List.of(
            toolUsePartial("tool_use", "echo", Map.of("text", "hello")), stopPartial())));
        h.hookSystem().onBeforeTool("default", ctx -> BeforeToolResult.denyAndTerminate("not allowed"));

        var actions = driveCollectingActions(h, "denied call");

        // L1-⑤ as a sequence: the run ends right at the denied tool — the
        // terminating outcome surfaces as a FinishOperation, no follow-up
        // StreamAssistant, no re-loop on the same call.
        assertThat(labels(actions)).containsExactly(
            "ApplyPendingWrite(message)", "StreamAssistant(assistant,0)", "ApplyPendingWrite(message)",
            "TryFinishRun(tool_use)", "ExecuteTool(echo)", "FinishOperation(completed)");
    }

    @Test
    void finishOperationMarksEachRunEnd() {
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("read", Map.of(), new AtomicInteger()));
        var h = harness(registry, scriptedStreamFn(List.of(
            toolUsePartial("tool_use", "read", Map.of("path", "a.txt")), stopPartial())));

        // Queue a follow-up before driving so the second run (tool → stop) is
        // chained within the same drive loop.
        var actions = new ArrayList<Action>();
        var action = h.run("default", "read a.txt");
        action = h.peekAction("default");
        while (action != null && !(action instanceof Action.TryFinishRun)) {
            actions.add(action);
            action = h.executeAction("default", action);
        }
        h.followUp("default", "then stop");
        while (action != null) {
            actions.add(action);
            action = h.executeAction("default", action);
        }

        // Each run's terminal outcome is an explicit FinishOperation; the
        // follow-up is consumed as a ConsumeQueueItem between runs.
        assertThat(labels(actions)).containsSubsequence(
            "ApplyPendingWrite(message)", "TryFinishRun(tool_use)", "ExecuteTool(read)",
            "ApplyPendingWrite(message)", "TryFinishRun(completed)", "FinishOperation(completed)",
            "ConsumeQueueItem(followUp)");
        assertThat(h.lastAssistantMessage()).isNotNull();
    }

    @Test
    void consumeQueueItemStartsWithNextRun() {
        var registry = new ToolRegistry(null);
        var h = harness(registry, scriptedStreamFn(List.of(stopPartial())));
        h.nextRun("default", "queued message");

        var actions = new ArrayList<Action>();
        var action = h.peekAction("default");
        while (action != null) {
            actions.add(action);
            action = h.executeAction("default", action);
        }

        // The idle lane consumes the nextRun queue as the first action, tagged
        // with the queue it actually came from (docs/21 D10 — the nextRun drain
        // used to be labelled "followUp", which mis-tagged its consume record).
        assertThat(labels(actions)).startsWith("ConsumeQueueItem(nextRun)");
        // The queued prompt becomes a user message in the run.
        var messages = h.snapshot("default").transcript().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(e -> ((Entry.Message) e).message().content())
            .map(blocks -> blocks.isEmpty() ? "" : ((ContentBlock.TextContent) blocks.get(0)).text())
            .toList();
        assertThat(messages).contains("queued message");
    }

    // ── ⑥ abort 护栏（不变量 5 行为化）─────────────────────

    @Test
    void abortAfterToolExecutionNeverIssuesAnotherStreamAssistant() {
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", Map.of(), new AtomicInteger()));
        var h = harness(registry, scriptedStreamFn(List.of(
            toolUsePartial("tool_use", "echo", Map.of("text", "hello")), stopPartial())));

        // Drive up to (and past) the tool execution, then abort before the
        // follow-up LLM round would start.
        var actions = new ArrayList<Action>();
        var action = h.run("default", "go");
        action = h.peekAction("default");
        while (action != null && !(action instanceof Action.ExecuteTool)) {
            actions.add(action);
            action = h.executeAction("default", action);
        }
        actions.add(action);                             // ExecuteTool
        action = h.executeAction("default", action);     // ApplyPendingWrite(tool)
        h.abort("default");
        while (action != null) {
            actions.add(action);
            action = h.executeAction("default", action);
        }

        // Invariant 5: once aborted, the state machine produces no further
        // StreamAssistant (the guard transitions to CHECKPOINT instead).
        assertThat(actions.stream()
            .dropWhile(a -> !(a instanceof Action.ExecuteTool))
            .anyMatch(a -> a instanceof Action.StreamAssistant)).isFalse();
        // The run finalized as an aborted/error outcome.
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("aborted");
    }

    // ── ⑥ 不变量谓词单元测试 ─────────────────────────────

    @Test
    void invariantsHoldRejectsSilentlyDroppedToolCalls() {
        var lane = new LaneState();
        lane.phase = RunPhase.IDLE;
        lane.pendingToolCalls.add(new Action.ExecuteTool("c1", "x", Map.of()));
        assertThat(LoopInvariants.hold(lane, null)).isFalse();
    }

    @Test
    void invariantsHoldRejectsUnpersistedWritesAtRunEnd() {
        var lane = new LaneState();
        lane.phase = RunPhase.IDLE;
        lane.pendingWrites.add(new Entry.Message(
            "e", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("x"))), null));
        assertThat(LoopInvariants.hold(lane, null)).isFalse();
    }

    @Test
    void invariantsHoldRejectsPhaseActionMismatches() {
        // CHECKPOINT cannot yield a StreamAssistant.
        var ckpt = new LaneState();
        ckpt.phase = RunPhase.CHECKPOINT;
        assertThat(LoopInvariants.hold(ckpt, new Action.StreamAssistant("assistant", 0))).isFalse();
        // ASSISTANT cannot yield a TryFinishRun.
        var asst = new LaneState();
        asst.phase = RunPhase.ASSISTANT;
        assertThat(LoopInvariants.hold(asst, new Action.TryFinishRun("completed"))).isFalse();
        // IDLE can only yield a ConsumeQueueItem.
        var idle = new LaneState();
        idle.phase = RunPhase.IDLE;
        assertThat(LoopInvariants.hold(idle, new Action.StreamAssistant("assistant", 0))).isFalse();
        assertThat(LoopInvariants.hold(idle, new Action.FinishOperation("completed"))).isFalse();
    }

    @Test
    void invariantsHoldGateNewActionsToTheirPhases() {
        // ConsumeQueueItem is legal only while idle.
        var idle = new LaneState();
        idle.phase = RunPhase.IDLE;
        assertThat(LoopInvariants.hold(idle, new Action.ConsumeQueueItem("followUp", List.of()))).isTrue();
        var asst = new LaneState();
        asst.phase = RunPhase.ASSISTANT;
        asst.abortSignal = AbortSignal.create();
        assertThat(LoopInvariants.hold(asst, new Action.ConsumeQueueItem("steer", List.of()))).isFalse();
        var ckpt = new LaneState();
        ckpt.phase = RunPhase.CHECKPOINT;
        assertThat(LoopInvariants.hold(ckpt, new Action.ConsumeQueueItem("followUp", List.of()))).isFalse();
        // FinishOperation is legal only from CHECKPOINT.
        assertThat(LoopInvariants.hold(ckpt, new Action.FinishOperation("completed"))).isTrue();
        assertThat(LoopInvariants.hold(asst, new Action.FinishOperation("failed"))).isFalse();
    }

    @Test
    void invariantsHoldRejectsStreamingAfterAbort() {
        var lane = new LaneState();
        lane.phase = RunPhase.ASSISTANT;
        lane.abortSignal = AbortSignal.create();
        lane.abortSignal.abort();
        assertThat(LoopInvariants.hold(lane, new Action.StreamAssistant("assistant", 0))).isFalse();
    }

    @Test
    void invariantsHoldAcceptsSteadyStates() {
        var idle = new LaneState();
        idle.phase = RunPhase.IDLE;
        assertThat(LoopInvariants.hold(idle, null)).isTrue();

        var asst = new LaneState();
        asst.phase = RunPhase.ASSISTANT;
        asst.abortSignal = AbortSignal.create();
        assertThat(LoopInvariants.hold(asst, new Action.StreamAssistant("assistant", 0))).isTrue();
        assertThat(LoopInvariants.hold(asst, new Action.ExecuteTool("c", "x", Map.of()))).isTrue();
        assertThat(LoopInvariants.hold(asst, new Action.ApplyPendingWrite("message", "e"))).isTrue();

        var ckpt = new LaneState();
        ckpt.phase = RunPhase.CHECKPOINT;
        assertThat(LoopInvariants.hold(ckpt, new Action.TryFinishRun("completed"))).isTrue();
        assertThat(LoopInvariants.hold(ckpt, new Action.ApplyPendingWrite("message", "e"))).isTrue();
    }
}
