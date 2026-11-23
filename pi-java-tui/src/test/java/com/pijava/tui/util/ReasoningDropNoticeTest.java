package com.pijava.tui.util;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W7（pi interactive-mode.ts:473-523/:4384-4410）：withheld reasoning 统计、
 * 同类抑制（计数不增 ⇒ 不显示）与逐字文案。
 */
class ReasoningDropNoticeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Message.AssistantMessage withDiagnostic(String type, String detailsJson)
            throws Exception {
        return new Message.AssistantMessage(List.of(), StopReason.STOP, null,
            null, null, null, null, null, null,
            List.of(new AssistantMessageDiagnostic(type, 0, null,
                detailsJson == null ? null : MAPPER.readTree(detailsJson))),
            null, null, null, null, null, null);
    }

    @Test
    void countsAnthropicThinkingDropsWithReasons() throws Exception {
        var message = withDiagnostic("anthropic_input_transformations",
            "{\"transformations\":["
                + "{\"type\":\"thinking_dropped\",\"reason\":\"signature\"},"
                + "{\"type\":\"thinking_dropped\"},"
                + "{\"type\":\"redacted\"}]}");

        var dropped = ReasoningDropNotice.find(message).orElseThrow();
        assertThat(dropped.provider()).isEqualTo("Anthropic");
        assertThat(dropped.count()).isEqualTo(2);
        assertThat(dropped.reasons()).containsExactly("signature");
        assertThat(ReasoningDropNotice.text(dropped)).isEqualTo(
            "Anthropic dropped 2 thinking blocks: signature (details in session)");
    }

    @Test
    void openAiReasoningItemsUseSingularNounWhenOne() throws Exception {
        var message = withDiagnostic("openai_input_transformations",
            "{\"transformations\":[{\"type\":\"reasoning\"}]}");

        assertThat(ReasoningDropNotice.text(ReasoningDropNotice.find(message).orElseThrow()))
            .isEqualTo("OpenAI dropped 1 reasoning item (details in session)");
    }

    @Test
    void unknownTypeOrUnusableDetailsYieldNothing() throws Exception {
        assertThat(ReasoningDropNotice.find(withDiagnostic("plain", "{\"a\":1}"))).isEmpty();
        assertThat(ReasoningDropNotice.find(
            withDiagnostic("anthropic_input_transformations", null))).isEmpty();
        assertThat(ReasoningDropNotice.find(withDiagnostic(
            "anthropic_input_transformations", "{\"transformations\":{}}"))).isEmpty();
        assertThat(ReasoningDropNotice.find(new Message.AssistantMessage(List.of()))).isEmpty();
    }

    /** 同一 provider/类型且计数不增 ⇒ 抑制；计数变多或换类型 ⇒ 显示。 */
    @Test
    void repeatsAreSuppressedOnlyWhenTheCountDoesNotGrow() throws Exception {
        var one = ReasoningDropNotice.find(withDiagnostic("anthropic_input_transformations",
            "{\"transformations\":[{\"type\":\"thinking_dropped\"}]}")).orElseThrow();
        var two = ReasoningDropNotice.find(withDiagnostic("anthropic_input_transformations",
            "{\"transformations\":[{\"type\":\"thinking_dropped\"},"
                + "{\"type\":\"thinking_dropped\"}]}")).orElseThrow();
        var openAi = ReasoningDropNotice.find(withDiagnostic("openai_input_transformations",
            "{\"transformations\":[{\"type\":\"reasoning\"}]}")).orElseThrow();

        assertThat(ReasoningDropNotice.isRepeat(one, Optional.of(two)))
            .as("计数不增（1 ≤ 2）").isTrue();
        assertThat(ReasoningDropNotice.isRepeat(two, Optional.of(one)))
            .as("计数变多 ⇒ 新损失").isFalse();
        assertThat(ReasoningDropNotice.isRepeat(openAi, Optional.of(one)))
            .as("不同 provider/类型").isFalse();
        assertThat(ReasoningDropNotice.isRepeat(one, Optional.empty())).isFalse();
    }
}
