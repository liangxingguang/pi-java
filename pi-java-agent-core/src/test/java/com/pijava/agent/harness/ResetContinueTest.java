package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResetContinueTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        return harness((model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "done", partial),
            new StreamEvent.StreamDone("stop", null, partial))));
    }

    private static AgentHarness harness(StreamFn sf) {
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    @Test
    void resetClearsTranscriptAndQueuesWhenIdle() {
        var h = harness();
        h.prompt("one");
        h.followUp("default", "queued");
        h.reset("default");
        var snap = h.snapshot("default");
        assertThat(snap.transcript()).isEmpty();
        assertThat(snap.queues().followUp()).isEmpty();
        assertThat(snap.queues().steer()).isEmpty();
        assertThat(snap.queues().nextRun()).isEmpty();
        assertThat(snap.operation()).isNull();
    }

    @Test
    void resetWhileRunningThrows() {
        // prompt 阻塞，无法在调用方一侧撞上「运行中」；让流自己在中途调用 reset
        // （与 MidStreamAbortTest 的 abort 同一手法）。
        var holder = new java.util.concurrent.atomic.AtomicReference<AgentHarness>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        var h = harness((model, context, options) -> {
            try {
                holder.get().reset("default");
            } catch (Throwable t) {
                failure.set(t);
            }
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "done", partial),
                new StreamEvent.StreamDone("stop", null, partial)));
        });
        holder.set(h);

        h.prompt("start");

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void continueRunAppendsNoUserEntryAndRuns() {
        var h = harness();
        h.prompt("first");
        // reset, then seed a user-only transcript (simulating an interrupted flow)
        h.reset("default");
        var userEntry = new Entry.Message(java.util.UUID.randomUUID().toString(),
            0, null, null,
            new com.pijava.ai.message.Message.UserMessage(
                List.of(new ContentBlock.TextContent("interrupted question"))), null);
        h.seedTranscript("default", List.of(userEntry));
        int before = h.snapshot("default").transcript().size();
        h.continueRun("default", null);
        var entries = h.snapshot("default").transcript();
        assertThat(entries.size()).isGreaterThan(before);
        // only assistant entries appended (no new user entry)
        assertThat(entries.subList(before, entries.size()))
            .noneMatch(e -> e instanceof Entry.Message m && "user".equals(m.message().role()));
    }

    @Test
    void continueRunOnEmptyTranscriptThrows() {
        var h = harness();
        assertThatThrownBy(() -> h.continueRun("default", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no messages");
    }

    @Test
    void continueRunFromAssistantLastThrows() {
        var h = harness();
        h.prompt("go");
        assertThatThrownBy(() -> h.continueRun("default", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("assistant");
    }

    @Test
    void continueRunAfterUserEntryRuns() {
        var h = harness();
        h.prompt("warmup");
        h.reset("default");
        // seed user + toolResult tail (simulating a mid-tool-interruption)
        var userEntry = new Entry.Message(java.util.UUID.randomUUID().toString(),
            0, null, null,
            new com.pijava.ai.message.Message.UserMessage(
                List.of(new ContentBlock.TextContent("q"))), null);
        var toolResult = new Entry.Message(java.util.UUID.randomUUID().toString(),
            1, null, null,
            new com.pijava.ai.message.Message.ToolResultMessage(
                "call-1", "echo", List.of(new ContentBlock.TextContent("out")), false),
            null);
        h.seedTranscript("default", List.of(userEntry, toolResult));
        assertThat(h.continueRun("default", null)).isNotNull();
        assertThat(h.lastAssistantMessage()).isNotNull();
    }
}
