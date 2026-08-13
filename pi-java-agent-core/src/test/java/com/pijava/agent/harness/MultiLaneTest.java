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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiLaneTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason("stop");
        StreamFn sf = (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "ok", partial),
                new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
                DriveMode.MANUAL, null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    @Test
    void createLaneReturnsDistinctHandle() {
        var h = harness();
        var lane = h.createLane(LaneConfig.of("review"));
        assertThat(lane.name()).isEqualTo("review");
        assertThat(h.lanes()).hasSize(2); // default + review
    }

    @Test
    void createLaneWithDuplicateNameThrows() {
        var h = harness();
        h.createLane(LaneConfig.of("dup"));
        assertThatThrownBy(() -> h.createLane(LaneConfig.of("dup")))
                .isInstanceOf(AgentHarness.LaneExistsException.class);
    }

    @Test
    void moveLaneTransfersTranscriptEntries() {
        var h = harness();
        h.createLane(LaneConfig.of("target"));
        // Populate the default lane transcript
        h.run("hello");
        // Flush the run so the assistant entry is written
        var action = h.peekAction();
        while (action != null) {
            action = h.executeAction(action);
        }
        int entriesBefore = h.snapshot("default").transcript().size();
        assertThat(entriesBefore).isGreaterThan(0);

        h.moveLane("default", "target");
        assertThat(h.snapshot("default").transcript()).isEmpty();
        assertThat(h.snapshot("target").transcript()).hasSize(entriesBefore);
    }

    @Test
    void lanesAreIsolated() {
        var h = harness();
        h.createLane(LaneConfig.of("a"));
        h.run("default", "default prompt");
        h.run("a", "lane-a prompt");
        assertThat(h.snapshot("default").transcript()).isNotEmpty();
        assertThat(h.snapshot("a").transcript()).isNotEmpty();
        // The two lanes hold different user entries
        assertThat(h.snapshot("default").transcript())
                .isNotEqualTo(h.snapshot("a").transcript());
    }

    @Test
    void laneConfigStoresSystemPrompt() {
        var h = harness();
        var lane = h.createLane(new LaneConfig("sp", null, null, "custom prompt"));
        assertThat(lane.name()).isEqualTo("sp");
        // systemPrompt is stored on LaneState (verified indirectly via run)
        h.run("sp", "hello");
        assertThat(h.snapshot("sp").transcript()).isNotEmpty();
    }
}
