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

/**
 * End-to-end integration: multi-lane + hook + compaction in one flow.
 */
class AgentHarnessIntegrationTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static StreamFn streamFn(String reply) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(reply)))
                .withStopReason("stop");
        return (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, reply, partial),
                new StreamEvent.StreamDone("stop", null, partial)));
    }

    private static AgentHarness harness() {
        return AgentHarness.create(new HarnessConfig(
                streamFn("assistant reply"), MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
                DriveMode.MANUAL, null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty()));
    }

    private static void drive(AgentHarness h, String lane, String prompt) {
        h.run(lane, prompt);
        var action = h.peekAction(lane);
        while (action != null) {
            action = h.executeAction(lane, action);
        }
    }

    @Test
    void multiLaneRunWithHookAndCompaction() {
        var h = harness();
        h.createLane(LaneConfig.of("review"));
        h.createLane(LaneConfig.of("edit"));

        // Register a hook that fires on every run end
        var runEndCount = new int[1];
        h.onBeforeRunEnd("review", ctx -> runEndCount[0]++);

        // Run on two lanes
        drive(h, "review", "review this code");
        drive(h, "edit", "edit this file");

        assertThat(runEndCount[0]).isEqualTo(1);
        assertThat(h.snapshot("review").transcript()).isNotEmpty();
        assertThat(h.snapshot("edit").transcript()).isNotEmpty();

        // Compaction on the review lane after more turns
        drive(h, "review", "one more turn");
        var before = h.snapshot("review").transcript().size();
        h.compact("review", new CompactionSettings(100_000, 0.5, true, true));
        var after = h.snapshot("review").transcript().size();
        assertThat(after).isLessThanOrEqualTo(before);
    }

    @Test
    void watchReceivesSnapshotOnStateChange() {
        var h = harness();
        var updates = new int[1];
        var handle = h.watch("default");
        handle.subscribe(snapshot -> updates[0]++);

        drive(h, "default", "hello");
        assertThat(updates[0]).isGreaterThan(0);
    }
}
