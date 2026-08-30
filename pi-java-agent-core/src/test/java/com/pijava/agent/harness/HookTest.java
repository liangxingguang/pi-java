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
        return (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, text, partial),
                new StreamEvent.StreamDone("stop", null, partial)));
    }

    private static HarnessConfig configWith(StreamFn sf) {
        return new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
                DriveMode.MANUAL, null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { });
    }

    /** Run a single turn to completion (so all wired hooks fire). */
    private static void driveToCompletion(AgentHarness h, String prompt) {
        h.run(prompt);
        var action = h.peekAction();
        while (action != null) {
            action = h.executeAction(action);
        }
    }

    @Test
    void beforeRunHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRun("default", ctx -> fired[0] = true);
        h.run("hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void beforeRequestHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRequest("default", ctx -> fired[0] = true);
        driveToCompletion(h, "hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void afterResponseHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onAfterResponse("default", ctx -> fired[0] = true);
        driveToCompletion(h, "hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void beforeRunEndHookFiresAtRunEnd() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRunEnd("default", ctx -> fired[0] = true);
        driveToCompletion(h, "hello");
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
        driveToCompletion(h, "hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void throwingHookIsNonFatal() {
        var h = harness();
        h.hookSystem().onBeforeRun("default", ctx -> {
            throw new RuntimeException("boom");
        });
        // Should not throw; hook errors are swallowed and recorded
        driveToCompletion(h, "hello");
        assertThat(h.lastAssistantMessage()).isNotNull();
    }

    @Test
    void shouldStopAfterTurnTruePreventsNextTurn() {
        var h = harness();
        var calls = new int[] {0};
        StreamFn counting = (messages, model, options) -> {
            calls[0]++;
            return stream("stop-me").stream(messages, model, options);
        };
        var cfg = configWith(counting);
        var hookHarness = com.pijava.agent.harness.AgentHarness.create(cfg);
        hookHarness.hookSystem().onShouldStopAfterTurn("default", ctx -> Boolean.TRUE);
        hookHarness.run("go");
        hookHarness.followUp("default", "second");
        var action = hookHarness.peekAction();
        while (action != null) { action = hookHarness.executeAction(action); }
        // hook returned TRUE → run finished in ONE stream call
        assertThat(calls[0]).isEqualTo(1);
        // and the follow-up queued before TryFinishRun was NOT drained by it
        assertThat(hookHarness.snapshot("default")
            .queues().followUp()).hasSize(1);
    }

    @Test
    void shouldStopAfterTurnNullAbstains() {
        var h = harness();
        h.hookSystem().onShouldStopAfterTurn("default", ctx -> null);
        driveToCompletion(h, "go");
        h.followUp("default", "second");
        assertThat(h.peekAction("default")).isNotNull();
    }

    @Test
    void throwingShouldStopHookAbstains() {
        var h = harness();
        h.hookSystem().onShouldStopAfterTurn("default", ctx -> {
            throw new RuntimeException("boom");
        });
        driveToCompletion(h, "go");
        h.followUp("default", "second");
        assertThat(h.peekAction("default")).isNotNull();
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
        return (messages, model, options) -> {
            var partial = calls.incrementAndGet() % 2 == 1 ? toolUse : stop;
            seenModels.add(model.provider() + "/" + model.modelName());
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "x", partial),
                new StreamEvent.StreamDone(partial.stopReason(), null, partial)));
        };
    }

    private static void drive(AgentHarness h) {
        var action = h.peekAction();
        while (action != null) { action = h.executeAction(action); }
    }

    @Test
    void prepareNextTurnSwitchesModelForNextTurnWithinRun() {
        var seenModels = new java.util.ArrayList<String>();
        var h = AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onPrepareNextTurn("default", ctx ->
            new com.pijava.agent.hook.TurnUpdate(ModelId.of("faux", "next-model"), null));
        h.run("go");
        drive(h);
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
        h.run("run1");
        drive(h);
        // reset model for run 2 (hook fired in run1 must NOT carry over)
        h.setModel(ModelId.of("faux", "test-model"));
        h.run("run2");
        drive(h);
        assertThat(seenModels).containsExactly(
            "faux/test-model", "faux/next-model",
            "faux/test-model", "faux/next-model");
    }

    @Test
    void prepareNextTurnNullChangesNothing() {
        var seenModels = new java.util.ArrayList<String>();
        var h = AgentHarness.create(configWith(toolUseThenStopStreamFn(seenModels)));
        h.hookSystem().onPrepareNextTurn("default", ctx -> null);
        h.run("go");
        drive(h);
        assertThat(seenModels).containsExactly("faux/test-model", "faux/test-model");
        assertThat(h.snapshot("default").transcript().stream()
            .noneMatch(e -> e instanceof com.pijava.agent.entry.Entry.ModelChange)).isTrue();
    }
}
