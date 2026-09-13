package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        return (model, context, options) -> StreamIterator.from(List.of(
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
        return (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.StreamError("error",
                        new RuntimeException(errorMsg), partial)
        ));
    }

    private AgentHarness createHarness(StreamFn sf) {
        return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
                null, java.util.Map.of(), com.pijava.ai.http.RetryPolicy.defaultPolicy(), com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(), QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(), event -> { }));
    }

    // ── Turn semantics ───────────────────────────────────────

    @Test
    void fullRunCycleReturnsResponse() {
        var harness = createHarness(textStreamFn("Hi there!"));
        harness.prompt("Hello");

        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
                .isEqualTo("Hi there!");
        // A finished run leaves the lane idle (pi: activeRun cleared on finish).
        assertThat(harness.snapshot("default").operation()).isNull();
    }

    @Test
    void errorStreamReturnsErrorPartial() {
        var harness = createHarness(errorStreamFn("connection refused"));
        harness.prompt("test");

        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("error");
    }

    /**
     * pi's {@code Agent.prompt} refuses a lane that is already processing
     * ("Agent is already processing."). The run has to be in flight on another
     * thread for that to be observable now — {@code prompt} is blocking, so a
     * single-threaded caller can never race itself.
     */
    @Test
    void promptWhenNotIdleThrows() throws Exception {
        var inStream = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inner = textStreamFn("ok");
        var harness = createHarness((model, context, options) -> {
            inStream.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return inner.stream(model, context, options);
        });

        var failure = new AtomicReference<Throwable>();
        var driver = new Thread(() -> {
            try {
                harness.prompt("first");
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        driver.start();
        assertThat(inStream.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> harness.prompt("second"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not idle");

        release.countDown();
        driver.join(10_000);
        assertThat(failure.get()).isNull();
    }

    /** {@code waitForIdle} blocks until the in-flight run settles, then returns. */
    @Test
    void waitForIdleReturnsAfterTheRunSettles() throws Exception {
        var inStream = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inner = textStreamFn("ok");
        var harness = createHarness((model, context, options) -> {
            inStream.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return inner.stream(model, context, options);
        });

        var driver = new Thread(() -> harness.prompt("go"));
        driver.start();
        assertThat(inStream.await(10, TimeUnit.SECONDS)).isTrue();

        var waiter = new Thread(() -> harness.waitForIdle("default"));
        waiter.start();
        // Still running ⇒ the waiter must still be parked.
        waiter.join(200);
        assertThat(waiter.isAlive()).isTrue();

        release.countDown();
        waiter.join(10_000);
        driver.join(10_000);
        assertThat(waiter.isAlive()).isFalse();
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
        assertThat(harness.laneName()).isEqualTo("default");
    }

    // ── Phase 2c: Hooks tests ─────────────────────────────────

    @Test
    void beforeRunHookIsFired() {
        var harness = createHarness(textStreamFn("ok"));
        var fired = new boolean[1];
        harness.hookSystem().onBeforeRun("default", ctx -> fired[0] = true);
        harness.prompt("test hook");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void hookErrorIsRecorded() {
        var harness = createHarness(textStreamFn("ok"));
        harness.hookSystem().onBeforeRun("default", ctx -> {
            throw new RuntimeException("hook exploded");
        });
        // Should not throw — hook errors are non-fatal
        harness.prompt("test");
        assertThat(harness.lastAssistantMessage()).isNotNull();
    }

    @Test
    void hookUnsubscriptionWorks() {
        var harness = createHarness(textStreamFn("ok"));
        var fired = new boolean[1];
        var handle = harness.hookSystem().onBeforeRun("default", ctx -> fired[0] = true);
        harness.prompt("test"); // this fires the hook → fired[0] = true
        assertThat(fired[0]).isTrue();
        try { handle.close(); } catch (Exception ignored) { }

        fired[0] = false;
        harness.prompt("test2"); // hook is unsubscribed, should not fire
        assertThat(fired[0]).isFalse();
    }

    // ── Phase 2c: Close tests ─────────────────────────────────

    @Test
    void closePreventsFurtherOperations() {
        var harness = createHarness(textStreamFn("ok"));
        harness.close();
        assertThatThrownBy(() -> harness.prompt("test"))
                .isInstanceOf(HarnessClosedException.class);
        assertThatThrownBy(() -> harness.compact(new CompactionSettings(true, 10, 10)))
                .isInstanceOf(HarnessClosedException.class);
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

        StreamFn allEventsFn = (model, context, options) -> StreamIterator.from(List.of(
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
        harness.prompt("test with all events");

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
        StreamFn errorFn = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "partial text", errorPartial
                        .withContent(List.of(new ContentBlock.TextContent("partial text")))),
                new StreamEvent.StreamError("error",
                        new RuntimeException("test error"), errorPartial)
        ));

        var harness = createHarness(errorFn);
        harness.prompt("trigger error");

        var result = harness.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("error");
    }
}
