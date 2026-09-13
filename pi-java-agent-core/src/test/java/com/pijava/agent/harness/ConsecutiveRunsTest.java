package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.record.LaneRecord;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consecutive runs on one lane: each run opens exactly one operation and closes
 * it itself, so the lane is reusable and storage never sees a leaked open
 * operation.
 */
class ConsecutiveRunsTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("done")))
                .withStopReason("stop");
        StreamFn sf = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "done", partial),
                new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    /**
     * Regression: each run's OperationStarted must reuse the lane runId so the
     * session layer can pair it with OperationFinished. A separate UUID
     * (pre-alignment) made operation_finished.runId never match the started id,
     * leaking an open operation that crashed the SECOND run of the same session
     * with "Lane already has an open operation" — the web UI's lost last turn.
     */
    @Test
    void consecutiveRunsNeverLeaveStaleOpenOperation() {
        var h = harness();

        h.prompt("first");
        var first = h.snapshot(AgentHarness.DEFAULT_LANE).records().stream()
            .filter(r -> r instanceof LaneRecord.OperationFinished).toList();
        assertThat(first).hasSize(1);

        h.prompt("second");
        var all = h.snapshot(AgentHarness.DEFAULT_LANE).records();

        // Second run opened a fresh operation that is closed by its own finish.
        var finishedIds = all.stream()
            .filter(r -> r instanceof LaneRecord.OperationFinished)
            .map(r -> ((LaneRecord.OperationFinished) r).runId())
            .toList();
        var startedIds = all.stream()
            .filter(r -> r instanceof LaneRecord.OperationStarted)
            .map(r -> r.id())
            .toList();
        assertThat(finishedIds).containsExactlyElementsOf(startedIds);
    }

    /** A blocking prompt leaves the lane idle again, ready for the next one. */
    @Test
    void laneIsIdleAfterEachRun() {
        var h = harness();
        h.prompt("first");
        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).operation()).isNull();
        h.prompt("second");
        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).operation()).isNull();
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }
}
