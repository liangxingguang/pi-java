package com.pijava.agent.loop;

import java.util.List;
import java.util.Set;

import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.HarnessConfig;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    @Test
    void singleTurnReturnsAssistantMessage() {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("Hello, World!")))
                .withStopReason("stop");
        var harness = AgentHarness.create(new HarnessConfig(
                (messages, model, options) -> StreamIterator.from(List.of(
                        new StreamEvent.Start(AssistantMessage.empty()),
                        new StreamEvent.TextStart(0, partial.withContent(
                                List.of(new ContentBlock.TextContent("")))),
                        new StreamEvent.TextDelta(0, "Hello, World!",
                                partial.withStopReason(null)),
                        new StreamEvent.TextEnd(0, "Hello, World!",
                                partial.withStopReason(null)),
                        new StreamEvent.StreamDone("stop", null, partial)
                )),
                MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null));

        var loop = new AgentLoop(harness);
        var result = loop.run("How are you?");

        assertThat(result).isNotNull();
        assertThat(result.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
                .isEqualTo("Hello, World!");
        assertThat(result.stopReason()).isEqualTo("stop");
    }

    @Test
    void errorTurnReturnsErrorPartial() {
        var partial = AssistantMessage.empty().withStopReason("error");
        var harness = AgentHarness.create(new HarnessConfig(
                (messages, model, options) -> StreamIterator.from(List.of(
                        new StreamEvent.Start(AssistantMessage.empty()),
                        new StreamEvent.StreamError("error",
                                new RuntimeException("boom"), partial)
                )),
                MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null));

        var loop = new AgentLoop(harness);
        var result = loop.run("test");

        assertThat(result).isNotNull();
        assertThat(result.stopReason()).isEqualTo("error");
    }
}
