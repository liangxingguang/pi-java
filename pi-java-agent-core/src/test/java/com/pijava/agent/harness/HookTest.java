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

class HookTest {

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

    /** Run a single turn to completion (so all wired hooks fire). */
    private static void driveToCompletion(AgentHarness h, String prompt) {
        h.run(prompt);
        var action = h.peekAction();
        while (action != null) {
            action = h.executeAction(action);
        }
    }

    @Test
    void beforeRunHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRun("default", ctx -> fired[0] = true);
        h.run("hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void beforeRequestHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRequest("default", ctx -> fired[0] = true);
        driveToCompletion(h, "hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void afterResponseHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onAfterResponse("default", ctx -> fired[0] = true);
        driveToCompletion(h, "hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void beforeRunEndHookFiresAtRunEnd() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onBeforeRunEnd("default", ctx -> fired[0] = true);
        driveToCompletion(h, "hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void transformContextHookFires() {
        var h = harness();
        var fired = new boolean[1];
        h.hookSystem().onTransformContext("default", messages -> {
            fired[0] = true;
            return messages;
        });
        driveToCompletion(h, "hello");
        assertThat(fired[0]).isTrue();
    }

    @Test
    void throwingHookIsNonFatal() {
        var h = harness();
        h.hookSystem().onBeforeRun("default", ctx -> {
            throw new RuntimeException("boom");
        });
        // Should not throw; hook errors are swallowed and recorded
        driveToCompletion(h, "hello");
        assertThat(h.lastAssistantMessage()).isNotNull();
    }
}
