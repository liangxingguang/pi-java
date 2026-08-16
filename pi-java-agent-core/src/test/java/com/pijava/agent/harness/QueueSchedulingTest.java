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

/**
 * Phase 3 queue scheduling: steer / followUp / nextRun / cancelQueued
 * enqueueing, cancellation, and mode-aware consumption.
 */
class QueueSchedulingTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason("stop");
        StreamFn sf = (messages, model, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "done", partial),
            new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, null, null, null,
            DriveMode.MANUAL, null, java.util.Map.of(),
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

    private static List<String> userMessages(AgentHarness h, String lane) {
        return h.snapshot(lane).transcript().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(e -> ((Entry.Message) e).message().content())
            .map(blocks -> blocks.isEmpty() ? "" : ((ContentBlock.TextContent) blocks.get(0)).text())
            .toList();
    }

    @Test
    void nextRunStartsRunWhenIdle() {
        var h = harness();
        h.nextRun("default", "queued message");

        drive(h, "default");

        assertThat(userMessages(h, "default")).contains("queued message");
        assertThat(h.lastAssistantMessage()).isNotNull();
    }

    @Test
    void cancelQueuedClearsNextRun() {
        var h = harness();
        h.nextRun("default", "will be cancelled");
        h.cancelQueued("default", "nextRun");

        drive(h, "default");

        assertThat(userMessages(h, "default")).isEmpty();
    }

    @Test
    void cancelQueuedRejectsUnknownType() {
        var h = harness();
        h.nextRun("default", "x");
        try {
            h.cancelQueued("default", "bogus");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    void steerQueuedBeforeRunIsInjectedAsUserMessage() {
        var h = harness();
        h.steer("default", "steering message");

        drive(h, "default");

        assertThat(userMessages(h, "default"))
            .containsExactly("steering message");
    }

    @Test
    void followUpQueuedDuringRunStartsNextRun() {
        var h = harness();
        var action = h.run("default", "first prompt");
        // Drive far enough that the first assistant reply is in flight, then queue.
        while (action != null && !(action instanceof Action.TryFinishRun)) {
            action = h.executeAction("default", action);
        }
        h.followUp("default", "follow-up prompt");
        drive(h, "default");

        assertThat(userMessages(h, "default"))
            .containsExactly("follow-up prompt");
        assertThat(h.lastAssistantMessage()).isNotNull();
    }

    @Test
    void oneAtATimeLeavesRemainingFollowUpsQueued() {
        var h = harness();
        h.followUp("default", "first");
        h.followUp("default", "second");
        h.followUp("default", "third");

        drive(h, "default");

        // One-at-a-time: each run drains exactly one message; the runs chain
        // until the queue is empty. run() clears the transcript per run, so the
        // final transcript holds only the last processed prompt.
        assertThat(userMessages(h, "default")).containsExactly("third");
        assertThat(h.snapshot("default").queues().followUp()).isEmpty();
    }

    @Test
    void oneAtATimeStopsWhenRunFinishesWithoutQueue() {
        var h = harness();
        h.followUp("default", "only");

        drive(h, "default");

        assertThat(userMessages(h, "default")).containsExactly("only");
        assertThat(h.snapshot("default").operation()).isNull();
        assertThat(h.snapshot("default").queues().followUp()).isEmpty();
    }

    @Test
    void allModeDrainsEntireQueue() {
        var h = harness();
        h.followUpMode(new QueueMode.All());
        h.followUp("default", "first");
        h.followUp("default", "second");

        drive(h, "default");

        assertThat(userMessages(h, "default"))
            .containsExactly("first\n\nsecond");
        assertThat(h.snapshot("default").queues().followUp()).isEmpty();
    }

    @Test
    void streamListenerReceivesEvents() throws Exception {
        var h = harness();
        var received = new java.util.ArrayList<StreamEvent>();
        try (var registration = h.onStreamEvent(received::add)) {
            h.run("default", "hello");
            drive(h, "default");
        }

        assertThat(received).isNotEmpty();
        assertThat(received).anyMatch(e -> e instanceof StreamEvent.TextEnd);
    }
}
