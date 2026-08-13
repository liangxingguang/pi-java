package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingConfig;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ThinkingTranslationTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    @Test
    void thinkingLevelMapIsUsedForTranslation() {
        var captured = new AtomicReference<StreamOptions>();
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason("stop");
        StreamFn sf = (messages, model, options) -> {
            captured.set(options);
            return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.TextEnd(0, "ok", partial),
                    new StreamEvent.StreamDone("stop", null, partial)));
        };

        var map = ThinkingLevelMap.of(Map.of(
                new ThinkingLevel.High(), ThinkingConfig.withBudget(9999)));
        var harness = AgentHarness.create(new HarnessConfig(
                sf, MODEL, new ModelThinkingLevel.Enabled(new ThinkingLevel.High()), "",
                Set.of(), 200_000, null, null, null,
                DriveMode.MANUAL, null, Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, map,
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode()));

        harness.run("hello");
        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        assertThat(captured.get().thinking().budgetTokens()).hasValue(9999);
    }

    @Test
    void emptyThinkingLevelMapDisablesThinking() {
        var captured = new AtomicReference<StreamOptions>();
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason("stop");
        StreamFn sf = (messages, model, options) -> {
            captured.set(options);
            return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.TextEnd(0, "ok", partial),
                    new StreamEvent.StreamDone("stop", null, partial)));
        };

        var harness = AgentHarness.create(new HarnessConfig(
                sf, MODEL, new ModelThinkingLevel.Enabled(new ThinkingLevel.High()), "",
                Set.of(), 200_000, null, null, null,
                DriveMode.MANUAL, null, Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
                ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode()));

        harness.run("hello");
        var action = harness.peekAction();
        while (action != null) {
            action = harness.executeAction(action);
        }

        assertThat(captured.get().thinking().enabled()).isFalse();
    }
}
