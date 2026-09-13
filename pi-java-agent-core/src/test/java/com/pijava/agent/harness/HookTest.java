package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
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
                .withStopReason("stop");
        return (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, text, partial),
                new StreamEvent.StreamDone("stop", null, partial)));
    }

    private static HarnessConfig configWith(StreamFn sf) {
        return new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
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
    void shouldStopAfterTurnTruePreventsNextTurn() {
        var h = harness();
        var calls = new int[] {0};
        StreamFn counting = (model, context, options) -> {
            calls[0]++;
            return stream("stop-me").stream(model, context, options);
        };
        var cfg = configWith(counting);
        var hookHarness = com.pijava.agent.harness.AgentHarness.create(cfg);
        hookHarness.hookSystem().onShouldStopAfterTurn("default", ctx -> Boolean.TRUE);
        hookHarness.followUp("default", "second");
        hookHarness.prompt("go");
        // hook returned TRUE → run finished in ONE stream call
        assertThat(calls[0]).isEqualTo(1);
        // and the follow-up queued before the run was NOT drained by it
        assertThat(hookHarness.snapshot("default")
            .queues().followUp()).hasSize(1);
    }

    @Test
    void shouldStopAfterTurnNullAbstains() {
        var seenModels = new java.util.ArrayList<String>();
        var h = com.pijava.agent.harness.AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onShouldStopAfterTurn("default", ctx -> null);
        h.prompt("go");
        // 弃权 ⇒ 循环继跑：tool_use 那一轮之后还有第二次请求。
        assertThat(seenModels).hasSize(2);
    }

    @Test
    void throwingShouldStopHookAbstains() {
        var seenModels = new java.util.ArrayList<String>();
        var h = com.pijava.agent.harness.AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onShouldStopAfterTurn("default", ctx -> {
            throw new RuntimeException("boom");
        });
        h.prompt("go");
        // 抛异常等同于弃权 ⇒ 循环继跑。
        assertThat(seenModels).hasSize(2);
    }

    // ── prepare_next_turn ─────────────────────────────────

    /** Multi-turn StreamFn: transcripts without a toolResult get tool_use; with one, stop. */
    private static StreamFn toolUseThenStopStreamFn(java.util.List<String> seenModels) {
        var toolUse = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.ToolUseContent(
                    "call-1", "echo", java.util.Map.of())))
                .withStopReason("tool_use");
        var stop = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("done")))
                .withStopReason("stop");
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
