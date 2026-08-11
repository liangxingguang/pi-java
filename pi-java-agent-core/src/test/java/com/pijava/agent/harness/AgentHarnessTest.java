package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

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
                Set.of(), 200_000));
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
        // run() returns the first AppendEntry; after flushing writes,
        // peekAction() should return StreamAssistant
        assertThat(firstAction).isNotNull();
        // Execute AppendEntry actions for initial entries, then expect StreamAssistant
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

        // Start run
        harness.run("Hello");
        assertThat(harness.peekAction()).isNotNull();

        // Drive to completion
        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        // Verify
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
    void deferredMethodsThrowUnsupportedOperation() {
        var harness = createHarness(textStreamFn("ok"));
        assertThatThrownBy(() -> harness.lane())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> harness.createLane(LaneConfig.of("test")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> harness.lanes())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> harness.drive())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> harness.runToCompletion())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> harness.getActiveTools())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> harness.compact(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Partial consumption tests ─────────────────────────────

    @Test
    void partialSnapshotUpdatedOnAllEventTypes() {
        // Create a stream with all 13 event types, each carrying a
        // progressively richer partial. The harness should overwrite
        // lane.partial on every event so lastAssistantMessage() reflects
        // the final StreamDone partial.
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
        // The final partial from StreamDone should be the one preserved
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

        // The error partial should be preserved
        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("error");
    }
}
