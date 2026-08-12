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

class RunToCompletionTest {

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
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty()));
    }

    @Test
    void runToCompletionThrowsInManualMode() {
        var h = harness();
        assertThatThrownBy(() -> h.runToCompletion())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void peekActionThrowsInAutomaticMode() {
        var h = harness();
        h.drive(new DriveMode.Automatic());
        assertThatThrownBy(() -> h.peekAction())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void executeActionThrowsInAutomaticMode() {
        var h = harness();
        h.drive(new DriveMode.Automatic());
        assertThatThrownBy(() -> h.executeAction(new Action.StreamAssistant("assistant", 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runToCompletionAutoCompletes() {
        var h = harness();
        h.drive(new DriveMode.Automatic());
        h.run("hello");
        h.runToCompletion().toCompletableFuture().join();
        assertThat(h.lastAssistantMessage()).isNotNull();
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }
}
