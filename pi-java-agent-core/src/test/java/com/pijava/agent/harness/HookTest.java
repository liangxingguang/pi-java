package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HookTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        return AgentHarness.create(configWith(stream("ok")));
    }

    private static StreamFn stream(String text) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason(StopReason.STOP);
        return (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, text, partial),
                new StreamEvent.StreamDone(StopReason.STOP, null, partial)));
    }

    private static HarnessConfig configWith(StreamFn sf) {
        return new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { });
    }

    /** Run a single turn to completion (so all wired hooks fire). */

    @Test
    void beforeRunHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRun("default", ctx -> fired[0] = true);
        h.prompt("hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void beforeRequestHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRequest("default", ctx -> fired[0] = true);
        h.prompt("hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void afterResponseHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onAfterResponse("default", ctx -> fired[0] = true);
        h.prompt("hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void beforeRunEndHookFiresAtRunEnd() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRunEnd("default", ctx -> fired[0] = true);
        h.prompt("hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void transformContextHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onTransformContext("default", messages -> {
            fired[0] = true;
            return messages;
        });
        h.prompt("hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void throwingHookIsNonFatal() {
        var h = harness();
        h.hookSystem().onBeforeRun("default", ctx -> {
            throw new RuntimeException("boom");
        });
        // Should not throw; hook errors are swallowed and recorded
        h.prompt("hello");
        assertThat(h.lastAssistantMessage()).isNotNull();
    }

    @Test
    void endDecisionEndsRunButPostRunContinueDrainsFollowUp() {
        // pi 真值（3c，原 docs/31 §8.21）：finishTurn 判 END 让内层循环在 followUp
        // 排空**之前**就 agent_end 返回（agent-loop.ts:289-292，:302 的排空被跳过）⇒
        // 运行内确实只烧了一次请求、队列确实没被运行内排空。但 _handlePostAgentRun
        // ③ 看到队列有货（agent-session.ts:1143）⇒ agent.continue()：副本以助手消息
        // 收尾 + followUp 有货 ⇒ 排空并按 runPromptMessages 再跑一个 pass
        // （agent.ts:372-381）。于是整场 prompt() 共 2 次请求，队列被排空。
        var h = harness();
        var calls = new int[] {0};
        StreamFn counting = (model, context, options) -> {
            calls[0]++;
            return stream("stop-me").stream(model, context, options);
        };
        var cfg = configWith(counting);
        var hookHarness = com.pijava.agent.harness.AgentHarness.create(cfg);
        hookHarness.hookSystem().onFinishTurn("default", ctx -> PiLoop.TurnDecision.END);
        hookHarness.followUp("default", "second");
        var outcome = hookHarness.prompt("go");
        // 运行内不排空（第一次请求不带 "second"），post-run continue 排空（第二次带）。
        assertThat(calls[0]).isEqualTo(2);
        assertThat(hookHarness.snapshot("default")
            .queues().followUp()).isEmpty();
        assertThat(outcome.passRunIds()).hasSize(2);
    }

    @Test
    void finishTurnNullAbstains() {
        var seenModels = new java.util.ArrayList<String>();
        var h = com.pijava.agent.harness.AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onFinishTurn("default", ctx -> null);
        h.prompt("go");
        // 弃权 ⇒ 循环继跑：toolUse 那一轮之后还有第二次请求。
        assertThat(seenModels).hasSize(2);
    }

    @Test
    void throwingFinishTurnHookAbstains() {
        var seenModels = new java.util.ArrayList<String>();
        var h = com.pijava.agent.harness.AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onFinishTurn("default", ctx -> {
            throw new RuntimeException("boom");
        });
        h.prompt("go");
        // 抛异常等同于弃权 ⇒ 循环继跑。
        assertThat(seenModels).hasSize(2);
    }

    // ── #41：多钩子合并（pi agent-session.ts:891-895）─────────────────

    @Test
    void finishTurnMergeEndWinsOverContinueInEitherOrder() {
        // pi：previousDecision?.action === "end" 直接赢 —— 与注册顺序无关。
        for (boolean endFirst : new boolean[] {true, false}) {
            var calls = new int[] {0};
            StreamFn counting = (model, context, options) -> {
                calls[0]++;
                return stream("stop").stream(model, context, options);
            };
            var h = com.pijava.agent.harness.AgentHarness.create(configWith(counting));
            if (endFirst) {
                h.hookSystem().onFinishTurn("default", ctx -> PiLoop.TurnDecision.END);
                h.hookSystem().onFinishTurn("default", ctx -> PiLoop.TurnDecision.CONTINUE);
            } else {
                h.hookSystem().onFinishTurn("default", ctx -> PiLoop.TurnDecision.CONTINUE);
                h.hookSystem().onFinishTurn("default", ctx -> PiLoop.TurnDecision.END);
            }
            h.prompt("go");
            assertThat(calls[0]).as("END 短路即赢（endFirst=" + endFirst + "）⇒ 只烧一次请求")
                .isEqualTo(1);
        }
    }

    @Test
    void finishTurnMergeContinueBeatsAbstain() {
        var calls = new int[] {0};
        StreamFn counting = (model, context, options) -> {
            calls[0]++;
            return stream("stop").stream(model, context, options);
        };
        var h = com.pijava.agent.harness.AgentHarness.create(configWith(counting));
        var hookCalls = new int[] {0};
        h.hookSystem().onFinishTurn("default", ctx -> null);
        h.hookSystem().onFinishTurn("default",
            // CONTINUE 只给一次 —— 否则每轮都白送，永不收口。
            ctx -> ++hookCalls[0] == 1 ? PiLoop.TurnDecision.CONTINUE : null);
        h.prompt("go");
        assertThat(calls[0]).as("无 END 有 CONTINUE ⇒ 白送一轮 context-only 请求，恰 2 次")
            .isEqualTo(2);
    }

    // ── prepare_next_turn ─────────────────────────────────

    /** Multi-turn StreamFn: transcripts without a toolResult get toolUse; with one, stop. */
    private static StreamFn toolUseThenStopStreamFn(java.util.List<String> seenModels) {
        var toolUse = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.ToolUseContent(
                    "call-1", "echo", java.util.Map.of())))
                .withStopReason(StopReason.TOOL_USE);
        var stop = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("done")))
                .withStopReason(StopReason.STOP);
        // Alternate per call: runs now append to the transcript across turns,
        // so "no ToolResult in context" can no longer identify a run's first turn.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        return (model, context, options) -> {
            var partial = calls.incrementAndGet() % 2 == 1 ? toolUse : stop;
            seenModels.add(model.provider() + "/" + model.modelName());
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "x", partial),
                new StreamEvent.StreamDone(partial.stopReason(), null, partial)));
        };
    }


    @Test
    void prepareNextTurnSwitchesModelForNextTurnWithinRun() {
        var seenModels = new java.util.ArrayList<String>();
        var h = AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onPrepareNextTurn("default", ctx ->
            new com.pijava.agent.hook.TurnUpdate(ModelId.of("faux", "next-model"), null));
        h.prompt("go");
        // turn 1 used test-model; turn 2 (same run) used next-model
        assertThat(seenModels).containsExactly("faux/test-model", "faux/next-model");
        // transcript records the switch (pi-java auditability)
        assertThat(h.snapshot("default").transcript().stream()
            .anyMatch(e -> e instanceof com.pijava.agent.entry.Entry.ModelChange mc
                && "next-model".equals(mc.modelId()))).isTrue();
    }

    @Test
    void prepareNextTurnDoesNotLeakAcrossRuns() {
        var seenModels = new java.util.ArrayList<String>();
        var h = AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onPrepareNextTurn("default", ctx ->
            new com.pijava.agent.hook.TurnUpdate(ModelId.of("faux", "next-model"), null));
        // run 1: turn1 test-model → hook fires → turn2 next-model → run ends
        h.prompt("run1");
        // reset model for run 2 (hook fired in run1 must NOT carry over)
        h.setModel(ModelId.of("faux", "test-model"));
        h.prompt("run2");
        assertThat(seenModels).containsExactly(
            "faux/test-model", "faux/next-model",
            "faux/test-model", "faux/next-model");
    }

    @Test
    void prepareNextTurnNullChangesNothing() {
        var seenModels = new java.util.ArrayList<String>();
        var h = AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onPrepareNextTurn("default", ctx -> null);
        h.prompt("go");
        assertThat(seenModels).containsExactly("faux/test-model", "faux/test-model");
        assertThat(h.snapshot("default").transcript().stream()
            .noneMatch(e -> e instanceof com.pijava.agent.entry.Entry.ModelChange)).isTrue();
    }
}
