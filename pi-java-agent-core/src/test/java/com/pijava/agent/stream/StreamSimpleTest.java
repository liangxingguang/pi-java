package com.pijava.agent.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.agent.harness.Context;
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

        StreamIterator iter = StreamSimple.stream(model, Context.of(messages),
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

        StreamIterator iter = StreamSimple.stream(model, Context.of(messages),
                ModelThinkingLevel.off(),
                (msgs, mdl, opts) -> StreamIterator.from(List.of()));

        var events = new ArrayList<StreamEvent>();
        while (iter.hasNext()) events.add(iter.next());
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.StreamError.class);
        var err = (StreamEvent.StreamError) events.get(1);
        assertThat(err.reason()).isEqualTo("error");
    }

    /**
     * 包H5 步1：{@link StreamSimple} 只把级别<b>原样</b>投下去，<b>不翻译</b>。
     *
     * <p>⚠️ 本方法取代了旧的 {@code translatesThinkingLevel}，并把断言<b>反过来</b>：
     * 旧断言钉「表把 {@code Low} 翻成 {@code budgetTokens=2048}」；那正是包H5 拆掉的
     * 错层行为（翻译要 {@code model.compat}/{@code thinkingLevelMap}，而这一层只有
     * {@code ModelInfo} 的<b>拷贝</b>，且 pi 的车道才做翻译）。</p>
     */
    @Test
    void passesThinkingLevelThroughUntranslated() {
        var thinkingMap = ThinkingLevelMap.of(Map.of(
                ModelThinkingLevel.of(new ThinkingLevel.Low()), Optional.of("low")
        ));
        var model = new ModelInfo(
                ModelId.of("anthropic", "claude"),
                "Claude",
                Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
                200_000, 4096, false, PricingInfo.UNKNOWN, thinkingMap);
        var messages = List.<Message>of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("think deep"))));

        var reasoningUsed = new java.util.concurrent.atomic.AtomicReference<Optional<ThinkingLevel>>();
        StreamIterator iter = StreamSimple.stream(model, Context.of(messages),
                ModelThinkingLevel.of(new ThinkingLevel.Low()),
                (msgs, mdl, opts) -> {
                    reasoningUsed.set(opts.reasoning());
                    var partial = AssistantMessage.empty().withStopReason("stop");
                    return StreamIterator.from(List.of(
                            new StreamEvent.Start(AssistantMessage.empty()),
                            new StreamEvent.StreamDone("stop", null, partial)
                    ));
                });

        // Consume iterator
        while (iter.hasNext()) iter.next();

        assertThat(reasoningUsed.get()).contains(new ThinkingLevel.Low());
    }
}
