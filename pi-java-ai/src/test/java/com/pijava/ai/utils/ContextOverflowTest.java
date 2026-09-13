package com.pijava.ai.utils;

import java.util.List;

import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code packages/ai/src/utils/overflow.ts} 的移植哨兵（package 3c，
 * {@code docs/31 §8.21}）。老 {@code OverflowDetector}（agent-core）的测试随
 * 实现一并删除 —— 它的期望值本身就与 pi 不符（见下 case 3 的注释）；这里以
 * pi 真值为准则重钉三情形与排除项。
 */
class ContextOverflowTest {

    private static Message.AssistantMessage error(String message) {
        return new Message.AssistantMessage(List.<ContentBlock>of(), "error", null,
            null, null, null, null, null, message);
    }

    private static Message.AssistantMessage with(String stopReason, Usage usage) {
        return new Message.AssistantMessage(List.<ContentBlock>of(), stopReason, null,
            null, null, null, usage, null, null);
    }

    private static Usage usage(double input, double output, double cacheRead) {
        return new Usage(input, output, cacheRead, 0, null, null,
            input + output + cacheRead, Usage.Cost.zero());
    }

    // ═══════════════════════════════════════════════════════════
    // Case 1：显式错误溢出（pi OVERFLOW_PATTERNS :37-63）
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "prompt is too long: 210000 tokens > 200000 maximum",
        "request_too_large",
        "input is too long for requested model",
        "This message exceeds the context window for this model",
        "Requested 300000 tokens exceeds the model's maximum context length of 200000 tokens",
        "input token count for the contents (300000) exceeds the maximum allowed (200000)",
        "maximum prompt length is 8192 characters",
        "Please reduce the length of the messages",
        "This model's maximum context length is 128000 tokens",
        "Request input (150000 tokens) exceeds the maximum allowed input length of 131072 tokens",
        "input (250000 tokens) is longer than the model's context length (131072 tokens)",
        "The input token count exceeds the limit of 8192",
        "Request exceeds the available context size",
        "Prompt is greater than the context length",
        "context window exceeds limit",
        "exceeded model token limit",
        "Prompt too large for model with 32768 maximum context length",
        "prompt has 150000 tokens, but the configured context size is 131072 tokens",
        "model_context_window_exceeded",
        "prompt too long; exceeded max context length by 2 tokens",
        "InvalidParameter: range of input length should be [1, 30000]",
        "context length exceeded",
        "context_length_exceeded",
        "too many tokens to process",
        "token limit exceeded",
        "413 status code (no body)",
        "400 (no body)",
    })
    void errorMessagesAreOverflow(String message) {
        assertThat(ContextOverflow.isContextOverflow(error(message), null)).isTrue();
    }

    // ═══════════════════════════════════════════════════════════
    // 排除项（pi NON_OVERFLOW_PATTERNS :74-78）：撞了溢出文本也不算
    // ═══════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "Throttling error: Account is being throttled, too many tokens for you",
        "Service unavailable: temporarily down",
        "rate limit exceeded, too many tokens",
        "Too many requests, please slow down",
    })
    void nonOverflowExclusionsWin(String message) {
        assertThat(ContextOverflow.isContextOverflow(error(message), null)).isFalse();
    }

    @Test
    void errorOnlyPatternsIgnoreOtherStopReasons() {
        // 模式判据只戴 error 收尾的帽子（overflow.ts:135）。
        var sameText = new Message.AssistantMessage(List.<ContentBlock>of(), "stop", null,
            null, null, null, null, null, "prompt is too long");
        assertThat(ContextOverflow.isContextOverflow(sameText, null)).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // Case 2：静默溢出（z.ai 型）—— stop 收尾但 input+cacheRead 越窗
    // ═══════════════════════════════════════════════════════════

    @Test
    void silentOverflowPastWindow() {
        var msg = with("stop", usage(210_000, 100, 0));
        assertThat(ContextOverflow.isContextOverflow(msg, 200_000)).isTrue();
        assertThat(ContextOverflow.isContextOverflow(msg, 300_000)).isFalse();
    }

    @Test
    void windowAbsentShortCases2And3() {
        // pi 的 `contextWindow &&`：undefined/0 ⇒ case 2/3 判不出（:145/:153）。
        var stop = with("stop", usage(210_000, 100, 0));
        assertThat(ContextOverflow.isContextOverflow(stop, null)).isFalse();
        assertThat(ContextOverflow.isContextOverflow(stop, 0)).isFalse();
        var length = with("length", usage(210_000, 0, 0));
        assertThat(ContextOverflow.isContextOverflow(length, null)).isFalse();
    }

    @Test
    void cacheReadCountsIntoSilentOverflow() {
        var msg = with("stop", usage(120_000, 100, 90_000));
        assertThat(ContextOverflow.isContextOverflow(msg, 200_000)).isTrue();
    }

    // ═══════════════════════════════════════════════════════════
    // Case 3：length 截断型（Xiaomi MiMo 型）—— 边界 0.99
    // ═══════════════════════════════════════════════════════════

    @Test
    void lengthTruncationAtOrAboveNinetyNinePercent() {
        // input == window*0.99 ⇒ `>=` 命中；再低一分 ⇒ 不命中（:155-157）。
        assertThat(ContextOverflow.isContextOverflow(
            with("length", usage(198_000, 0, 0)), 200_000)).isTrue();
        assertThat(ContextOverflow.isContextOverflow(
            with("length", usage(197_999, 0, 0)), 200_000)).isFalse();
    }

    @Test
    void lengthCaseNeedsZeroOutput() {
        // output>0 ⇒ 不是「截满窗口零产出」的形状。
        assertThat(ContextOverflow.isContextOverflow(
            with("length", usage(210_000, 5, 0)), 200_000)).isFalse();
    }

    @Test
    void oldDetectorExpectationsCorrected() {
        // 被删的 OverflowDetectorTest 钉过「input=1000/window=200000 + length
        // + output=0 ⇒ 溢出」—— pi 的 case 3 是 **>= window*0.99**，那条期望
        // 是错的；此处按 pi 真值重钉同一形状。
        assertThat(ContextOverflow.isContextOverflow(
            with("length", usage(1_000, 0, 0)), 200_000)).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // isRecoverableLength（overflow.ts:171-173）
    // ═══════════════════════════════════════════════════════════

    @Test
    void recoverableLengthOnlyBelowDesiredMax() {
        var truncated = with("length", usage(10_000, 500, 0));
        assertThat(ContextOverflow.isRecoverableLength(truncated, 8_192)).isTrue();
        // output 已达意图上限 ⇒ 正常截断，不恢复。
        var full = with("length", usage(10_000, 8_192, 0));
        assertThat(ContextOverflow.isRecoverableLength(full, 8_192)).isFalse();
        // 非 length 收尾、预算为 0（裁决④的「判不出」形状）、usage 缺席 ⇒ 恒 false。
        assertThat(ContextOverflow.isRecoverableLength(
            with("stop", usage(10_000, 500, 0)), 8_192)).isFalse();
        assertThat(ContextOverflow.isRecoverableLength(truncated, 0)).isFalse();
        assertThat(ContextOverflow.isRecoverableLength(
            with("length", null), 8_192)).isFalse();
    }
}
