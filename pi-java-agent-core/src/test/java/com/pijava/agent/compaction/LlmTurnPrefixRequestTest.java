package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B171（{@code docs/18}）：turn-prefix 摘要调用的请求形状 —— pi
 * {@code generateTurnPrefixSummary}（{@code compaction.ts:1076-1120}）：用户消息是
 * {@code # Conversation / # Instructions} 包装的逐字 {@code TURN_PREFIX_SUMMARIZATION_PROMPT}；
 * B272（docs/24 W2）后不再发固定输出上限（{@code maxTokens} 空 ⇒ 模型默认，pi #9512），
 * 并接 {@code /compact} 的 {@code Additional focus} 指令，缓存门 {@code none}。
 */
class LlmTurnPrefixRequestTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "summary");

    private static final List<Message> ONE = List.of(
        new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));

    /** pi TURN_PREFIX_SUMMARIZATION_PROMPT（compaction.ts:942-956）逐字。 */
    private static final String EXPECTED_PROMPT =
        "The messages above are earlier context from an ongoing conversation. Later messages "
        + "are stored separately and do not need to be reconstructed.\n\n"
        + "Create a concise checkpoint of the user's request and the progress shown above. "
        + "This checkpoint will be placed before the later messages so the conversation can "
        + "continue with the necessary context.\n\n"
        + "## Original Request\n[What did the user ask for?]\n\n"
        + "## Progress So Far\n- [Key decisions and work completed in these messages]\n\n"
        + "## Context Needed to Continue\n"
        + "- [Information from these messages needed to understand the later work]\n\n"
        + "Only summarize information explicitly present above. Do not infer or recreate "
        + "later messages.";

    private static List<StreamEvent> success(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    /** 捕获请求的脚本流。 */
    private static final class Captured {
        final List<Context> contexts = new ArrayList<>();
        final List<StreamOptions> options = new ArrayList<>();

        StreamFn fn() {
            return (model, context, options) -> {
                this.contexts.add(context);
                this.options.add(options);
                return StreamIterator.from(success("PREF"));
            };
        }

        String promptText() {
            Message first = contexts.get(0).messages().getFirst();
            ContentBlock block = first.content().getFirst();
            return ((ContentBlock.TextContent) block).text();
        }
    }

    private static LlmSummaryGenerator generator(StreamFn fn, IntSupplierCap cap) {
        return new LlmSummaryGenerator(fn, () -> MODEL,
            RetrySettings::defaults, () -> false, RetryObserver.NOOP, cap.asIntSupplier());
    }

    /** 小包装让测试里的 cap 字面量可读（{@code () -> N} 直接内联亦可）。 */
    private record IntSupplierCap(int value) {
        java.util.function.IntSupplier asIntSupplier() {
            return () -> value;
        }
    }

    @Test
    void sendsTheVerbatimTurnPrefixPrompt() {
        var captured = new Captured();

        generator(captured.fn(), new IntSupplierCap(0))
            .summarizeTurnPrefix(ONE, 16_384, "manual");

        assertThat(captured.promptText())
            .as("pi compaction.ts:1094 的 prompt 包装，逐字")
            .isEqualTo("# Conversation\n[User]: hi\n\n# Instructions\n" + EXPECTED_PROMPT);
        assertThat(captured.contexts.get(0).systemPrompt())
            .as("与历史摘要同一个系统提示")
            .isNotBlank();
        assertThat(captured.options.get(0).maxTokens())
            .as("B272：不再发固定上限，交给模型默认")
            .isEmpty();
        assertThat(captured.options.get(0).cacheRetention())
            .contains(CacheRetention.NONE);
    }

    @Test
    void customInstructionsAppendAdditionalFocus() {
        var captured = new Captured();

        generator(captured.fn(), new IntSupplierCap(4_096))
            .summarizeTurnPrefix(ONE, 16_384, "只保留测试相关的决定", "manual");

        assertThat(captured.promptText())
            .as("pi compaction.ts:1082-1085：turn-prefix 也接 /compact 指令")
            .isEqualTo("# Conversation\n[User]: hi\n\n# Instructions\n" + EXPECTED_PROMPT
                + "\n\nAdditional focus: 只保留测试相关的决定");
        assertThat(captured.options.get(0).maxTokens()).isEmpty();
    }
}
