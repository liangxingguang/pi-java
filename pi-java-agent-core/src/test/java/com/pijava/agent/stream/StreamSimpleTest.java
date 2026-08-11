package com.pijava.agent.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingConfig;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StreamSimpleTest {

    private static ModelInfo testModel() {
        return new ModelInfo(
                ModelId.of("faux", "test-model"),
                "Test Model",
                Set.of(ModelCapability.TEXT),
                200_000,
                4096,
                false,
                PricingInfo.UNKNOWN,
                ThinkingLevelMap.empty()
        );
    }

    @Test
    void normalStreamPassesThrough() {
        var model = testModel();
        var messages = List.<Message>of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hello"))));
        var donePartial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("hi")))
                .withStopReason("stop");

        StreamIterator iter = StreamSimple.stream(model, messages,
                ModelThinkingLevel.off(),
                (msgs, mdl, opts) -> StreamIterator.from(List.of(
                        new StreamEvent.Start(AssistantMessage.empty()),
                        new StreamEvent.StreamDone("stop", null, donePartial)
                )));

        var events = new ArrayList<StreamEvent>();
        while (iter.hasNext()) events.add(iter.next());
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Start.class);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.StreamDone.class);
    }

    @Test
    void overflowReturnsErrorEvent() {
        var model = new ModelInfo(
                ModelId.of("faux", "small-model"),
                "Small Model",
                Set.of(ModelCapability.TEXT),
                100,  // tiny window
                4096,
                false,
                PricingInfo.UNKNOWN,
                ThinkingLevelMap.empty()
        );
        // Large message that will overflow
        var longText = "x".repeat(10000);
        var messages = List.<Message>of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent(longText))));

        StreamIterator iter = StreamSimple.stream(model, messages,
                ModelThinkingLevel.off(),
                (msgs, mdl, opts) -> StreamIterator.from(List.of()));

        var events = new ArrayList<StreamEvent>();
        while (iter.hasNext()) events.add(iter.next());
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.StreamError.class);
        var err = (StreamEvent.StreamError) events.get(1);
        assertThat(err.reason()).isEqualTo("error");
    }

    @Test
    void translatesThinkingLevel() {
        var thinkingMap = ThinkingLevelMap.of(Map.of(
                new ThinkingLevel.Low(), ThinkingConfig.withBudget(2048)
        ));
        var model = new ModelInfo(
                ModelId.of("anthropic", "claude"),
                "Claude",
                Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
                200_000, 4096, false, PricingInfo.UNKNOWN, thinkingMap);
        var messages = List.<Message>of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("think deep"))));

        var thinkingUsed = new ThinkingConfig[1];
        StreamIterator iter = StreamSimple.stream(model, messages,
                ModelThinkingLevel.of(new ThinkingLevel.Low()),
                (msgs, mdl, opts) -> {
                    thinkingUsed[0] = opts.thinking();
                    var partial = AssistantMessage.empty().withStopReason("stop");
                    return StreamIterator.from(List.of(
                            new StreamEvent.Start(AssistantMessage.empty()),
                            new StreamEvent.StreamDone("stop", null, partial)
                    ));
                });

        // Consume iterator
        while (iter.hasNext()) iter.next();

        assertThat(thinkingUsed[0]).isNotNull();
        assertThat(thinkingUsed[0].enabled()).isTrue();
        assertThat(thinkingUsed[0].budgetTokens()).hasValue(2048);
    }
}
