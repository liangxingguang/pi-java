package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B173（{@code docs/22}）：摘要请求透传会话思考级别 —— pi
 * {@code createSummarizationOptions}（{@code compaction.ts:606-608}）：模型支持
 * reasoning、级别有值且非 {@code off} 时 {@code options.reasoning = level}，
 * 级别在每次请求时现读。历史摘要与 turn-prefix 两路共用。
 */
class LlmSummaryReasoningOptionTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "summary");

    private static final List<Message> ONE = List.of(
        new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));

    /** 捕获请求的脚本流。 */
    private static final class Captured {
        final List<StreamOptions> options = new ArrayList<>();

        StreamFn fn() {
            return (model, context, options) -> {
                this.options.add(options);
                return StreamIterator.from(success("SUMMARY"));
            };
        }
    }

    private static List<StreamEvent> success(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private static final IntSupplier NO_CAP = () -> 0;

    private static LlmSummaryGenerator generator(Captured captured,
            Supplier<ModelThinkingLevel> current, Predicate<ModelId<?>> supported) {
        return new LlmSummaryGenerator(captured.fn(), () -> MODEL,
            RetrySettings::defaults, () -> false, RetryObserver.NOOP, NO_CAP,
            current, supported);
    }

    @Test
    void historySummaryForwardsTheCurrentThinkingLevel() {
        var captured = new Captured();

        generator(captured, () -> ModelThinkingLevel.of(new ThinkingLevel.High()), id -> true)
            .summarize(ONE, null, null, 20_000, "manual");

        assertThat(captured.options.getFirst().reasoning())
            .as("pi :606-608：会话当前级别透传进摘要请求")
            .contains(new ThinkingLevel.High());
    }

    @Test
    void turnPrefixForwardsTheCurrentThinkingLevel() {
        var captured = new Captured();

        generator(captured, () -> ModelThinkingLevel.of(new ThinkingLevel.High()), id -> true)
            .summarizeTurnPrefix(ONE, 20_000, "manual");

        assertThat(captured.options.getFirst().reasoning())
            .as("turn-prefix 路共用同一道门")
            .contains(new ThinkingLevel.High());
    }

    @Test
    void modelWithoutReasoningDropsTheLevel() {
        var captured = new Captured();

        generator(captured, () -> ModelThinkingLevel.of(new ThinkingLevel.High()), id -> false)
            .summarize(ONE, null, null, 20_000, "manual");

        assertThat(captured.options.getFirst().reasoning())
            .as("model.reasoning === false ⇒ 不带 reasoning")
            .isEmpty();
    }

    @Test
    void offLevelDropsTheLevelEvenWhenSupported() {
        var captured = new Captured();

        generator(captured, ModelThinkingLevel::off, id -> true)
            .summarize(ONE, null, null, 20_000, "manual");

        assertThat(captured.options.getFirst().reasoning())
            .as("级别 off ⇒ 不传（pi :606 的 !== 'off' 门）")
            .isEmpty();
    }

    @Test
    void levelIsReadAtRequestTime() {
        var captured = new Captured();
        var current = new AtomicReference<ModelThinkingLevel>(
            ModelThinkingLevel.of(new ThinkingLevel.High()));
        var gen = generator(captured, current::get, id -> true);

        gen.summarize(ONE, null, null, 20_000, "manual");
        current.set(ModelThinkingLevel.of(new ThinkingLevel.Low()));
        gen.summarize(ONE, null, null, 20_000, "manual");

        assertThat(captured.options.get(0).reasoning())
            .contains(new ThinkingLevel.High());
        assertThat(captured.options.get(1).reasoning())
            .as("级别在 request 时现读，不做构造期快照")
            .contains(new ThinkingLevel.Low());
    }
}
