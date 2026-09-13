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

/**
 * Manual-drive single-turn semantics (previously covered by the removed
 * AgentLoop facade): drive peekAction/executeAction to completion, then read
 * the final assistant message.
 */
class ManualDriveTurnTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harnessWith(StreamFn sf) {
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

    private static void drive(AgentHarness h) {
        var action = h.peekAction();
        while (action != null) {
            action = h.executeAction(action);
        }
    }

    @Test
    void singleTurnReturnsAssistantMessage() {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("Hello, World!")))
            .withStopReason("stop");
        var h = harnessWith((model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, partial.withContent(
                List.of(new ContentBlock.TextContent("")))),
            new StreamEvent.TextDelta(0, "Hello, World!",
                partial.withStopReason(null)),
            new StreamEvent.TextEnd(0, "Hello, World!",
                partial.withStopReason(null)),
            new StreamEvent.StreamDone("stop", null, partial))));

        h.run("How are you?");
        drive(h);

        var result = h.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
            .isEqualTo("Hello, World!");
        assertThat(result.stopReason()).isEqualTo("stop");
    }

    @Test
    void errorTurnReturnsErrorPartial() {
        var partial = AssistantMessage.empty().withStopReason("error");
        var h = harnessWith((model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamError("error",
                new RuntimeException("boom"), partial))));

        h.run("test");
        drive(h);

        var result = h.lastAssistantMessage();
        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("error");
    }
}
