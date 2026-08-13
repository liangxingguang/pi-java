package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentHarnessTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    // ── Helpers ──────────────────────────────────────────────

    private static StreamFn textStreamFn(String text) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason("stop");
        return (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, partial.withContent(
                        List.of(new ContentBlock.TextContent("")))),
                new StreamEvent.TextDelta(0, text, partial.withStopReason(null)),
                new StreamEvent.TextEnd(0, text, partial.withStopReason(null)),
                new StreamEvent.StreamDone("stop", null, partial)
        ));
    }

    private static StreamFn errorStreamFn(String errorMsg) {
        var partial = AssistantMessage.empty().withStopReason("error");
        return (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.StreamError("error",
                        new RuntimeException(errorMsg), partial)
        ));
    }

    private AgentHarness createHarness(StreamFn sf) {
        return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
                DriveMode.MANUAL, null, java.util.Map.of(), com.pijava.ai.http.RetryPolicy.defaultPolicy(), com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(), QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode()));
    }

    // ── State machine tests ─────────────────────────────────

    @Test
    void peekActionWhenIdleReturnsNull() {
        var harness = createHarness(textStreamFn("hello"));
        assertThat(harness.peekAction()).isNull();
    }

    @Test
    void runTransitionsToAssistant() {
        var harness = createHarness(textStreamFn("hello"));
        var firstAction = harness.run("hello");
        assertThat(firstAction).isNotNull();
        var action = harness.peekAction();
        while (action instanceof Action.AppendEntry) {
            action = harness.executeAction(action);
        }
        assertThat(action).isInstanceOf(Action.StreamAssistant.class);
    }

    @Test
    void runWhenNotIdleThrows() {
        var harness = createHarness(textStreamFn("hello"));
        harness.run("first");
        assertThatThrownBy(() -> harness.run("second"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fullRunCycleReturnsResponse() {
        var harness = createHarness(textStreamFn("Hi there!"));
        harness.run("Hello");
        assertThat(harness.peekAction()).isNotNull();

        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
                .isEqualTo("Hi there!");
    }

    @Test
    void errorStreamReturnsErrorPartial() {
        var harness = createHarness(errorStreamFn("connection refused"));
        harness.run("test");
        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("error");
    }

    @Test
    void getModelReturnsConfiguredModel() {
        var harness = createHarness(textStreamFn("ok"));
        assertThat(harness.getModel()).isEqualTo(MODEL);
    }

    @Test
    void setModelUpdatesModel() {
        var harness = createHarness(textStreamFn("ok"));
        var newModel = ModelId.of("test", "new-model");
        harness.setModel(newModel);
        assertThat(harness.getModel()).isEqualTo(newModel);
    }

    @Test
    void getThinkingLevelReturnsOffByDefault() {
        var harness = createHarness(textStreamFn("ok"));
        assertThat(harness.getThinkingLevel()).isInstanceOf(ModelThinkingLevel.Off.class);
    }

    @Test
    void getActiveToolsReturnsConfiguredTools() {
        var harness = createHarness(textStreamFn("ok"));
        var tools = harness.getActiveTools();
        assertThat(tools).isEmpty();
    }

    // ── Phase 2c: Multi-lane tests ────────────────────────────

    @Test
    void defaultLaneIsCreatedOnConstruction() {
        var harness = createHarness(textStreamFn("ok"));
        var lane = harness.lane();
        assertThat(lane.name()).isEqualTo("default");
    }

    @Test
    void createLaneReturnsHandle() {
        var harness = createHarness(textStreamFn("ok"));
        var handle = harness.createLane(LaneConfig.of("review"));
        assertThat(handle.name()).isEqualTo("review");
    }

    @Test
    void createLaneWithExistingNameThrows() {
        var harness = createHarness(textStreamFn("ok"));
        harness.createLane(LaneConfig.of("review"));
        assertThatThrownBy(() -> harness.createLane(LaneConfig.of("review")))
                .isInstanceOf(AgentHarness.LaneExistsException.class);
    }

    @Test
    void lanesReturnsAllLanes() {
        var harness = createHarness(textStreamFn("ok"));
        harness.createLane(LaneConfig.of("a"));
        harness.createLane(LaneConfig.of("b"));
        assertThat(harness.lanes()).hasSize(3); // default + a + b
    }

    @Test
    void laneHandleRunDelegates() {
        var harness = createHarness(textStreamFn("Hi!"));
        var handle = harness.createLane(LaneConfig.of("lane1"));
        var action = handle.run("hello from lane1");
        assertThat(action).isNotNull();
    }

    // ── Phase 2c: Drive mode tests ────────────────────────────

    @Test
    void driveModeDefaultsToManual() {
        var harness = createHarness(textStreamFn("ok"));
        assertThat(harness.drive()).isInstanceOf(DriveMode.Manual.class);
    }

    @Test
    void setDriveModeToAutomatic() {
        var harness = createHarness(textStreamFn("ok"));
        harness.drive(new DriveMode.Automatic());
        assertThat(harness.drive()).isInstanceOf(DriveMode.Automatic.class);
    }

    @Test
    void runToCompletionThrowsInManualMode() {
        var harness = createHarness(textStreamFn("ok"));
        assertThatThrownBy(() -> harness.runToCompletion())
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Phase 2c: Hooks tests ─────────────────────────────────

    @Test
    void beforeRunHookIsFired() {
        var harness = createHarness(textStreamFn("ok"));
        var fired = new boolean[1];
        harness.onBeforeRun("default", ctx -> fired[0] = true);
        harness.run("test hook");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void hookErrorIsRecorded() {
        var harness = createHarness(textStreamFn("ok"));
        harness.onBeforeRun("default", ctx -> {
            throw new RuntimeException("hook exploded");
        });
        // Should not throw — hook errors are non-fatal
        harness.run("test");
        // Drive to completion
        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }
        assertThat(harness.lastAssistantMessage()).isNotNull();
    }

    @Test
    void hookUnsubscriptionWorks() {
        var harness = createHarness(textStreamFn("ok"));
        var fired = new boolean[1];
        var handle = harness.onBeforeRun("default", ctx -> fired[0] = true);
        harness.run("test"); // this fires the hook → fired[0] = true
        assertThat(fired[0]).isTrue();
        try { handle.close(); } catch (Exception ignored) {}

        // Drive first run to completion
        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        fired[0] = false;
        harness.run("test2"); // hook is unsubscribed, should not fire
        assertThat(fired[0]).isFalse();
    }

    // ── Phase 2c: Close tests ─────────────────────────────────

    @Test
    void closePreventsFurtherOperations() {
        var harness = createHarness(textStreamFn("ok"));
        harness.close();
        assertThatThrownBy(() -> harness.run("test"))
                .isInstanceOf(AgentHarness.HarnessClosedException.class);
        assertThatThrownBy(() -> harness.createLane(LaneConfig.of("x")))
                .isInstanceOf(AgentHarness.HarnessClosedException.class);
    }

    // ── Phase 2c: Skills tests ────────────────────────────────

    @Test
    void skillManagerIsAvailable() {
        var harness = createHarness(textStreamFn("ok"));
        assertThat(harness.skillManager()).isNotNull();
        assertThat(harness.skillManager().all()).isEmpty();
    }

    // ── Phase 2c: Compaction tests ────────────────────────────

    @Test
    void compactThrowsOnEmptyTranscript() {
        var harness = createHarness(textStreamFn("ok"));
        assertThatThrownBy(() ->
            harness.compact(CompactionSettings.defaults()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Partial consumption tests ─────────────────────────────

    @Test
    void partialSnapshotUpdatedOnAllEventTypes() {
        var finalPartial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("all events processed")))
                .withStopReason("stop");
        var emptyPartial = AssistantMessage.empty();

        StreamFn allEventsFn = (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(emptyPartial),
                new StreamEvent.TextStart(0, emptyPartial),
                new StreamEvent.TextDelta(0, "hello", emptyPartial
                        .withContent(List.of(new ContentBlock.TextContent("hello")))),
                new StreamEvent.TextEnd(0, "hello", emptyPartial
                        .withContent(List.of(new ContentBlock.TextContent("hello")))),
                new StreamEvent.ThinkingStart(1, emptyPartial
                        .withContent(List.of(new ContentBlock.TextContent("hello")))),
                new StreamEvent.ThinkingDelta(1, "hmm", emptyPartial
                        .withContent(List.of(new ContentBlock.TextContent("hello"),
                                new ContentBlock.TextContent("hmm")))),
                new StreamEvent.ThinkingEnd(1, "hmm", emptyPartial
                        .withContent(List.of(new ContentBlock.TextContent("hello"),
                                new ContentBlock.TextContent("hmm")))),
                new StreamEvent.StreamDone("stop", null, finalPartial)
        ));

        var harness = createHarness(allEventsFn);
        harness.run("test with all events");

        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("stop");
        assertThat(result.content()).isNotEmpty();
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
                .isEqualTo("all events processed");
    }

    @Test
    void errorEventPartialPreserved() {
        var errorPartial = AssistantMessage.empty()
                .withStopReason("error");
        StreamFn errorFn = (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "partial text", errorPartial
                        .withContent(List.of(new ContentBlock.TextContent("partial text")))),
                new StreamEvent.StreamError("error",
                        new RuntimeException("test error"), errorPartial)
        ));

        var harness = createHarness(errorFn);
        harness.run("trigger error");

        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("error");
    }
}
