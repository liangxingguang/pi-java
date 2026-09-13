package com.pijava.agent.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * package 3b 的估算器判据（pi {@code compaction.ts:164-310} 逐条，
 * {@code docs/31 §8.20}）：用量优先、无效用量跳过、{@code ceil(chars/4)}、
 * 图像常数、toolCall 的 JSON 长度、{@code safeJsonStringify} 的两级回退、
 * {@code shouldCompact} 的门。
 */
class ContextUsageEstimatorTest {

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistant(String text, String stopReason, Usage usage) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent(text)), stopReason, null,
            null, null, null, usage, null, null);
    }

    private static Usage total(double t) {
        return new Usage(0, 0, 0, 0, null, null, t, Usage.Cost.zero());
    }

    private static Usage parts(double input, double output, double cacheRead, double cacheWrite) {
        return new Usage(input, output, cacheRead, cacheWrite, null, null, 0, Usage.Cost.zero());
    }

    // ── calculateContextTokens ────────────────────────────────

    @Test
    void calculatePrefersTotalTokens() {
        assertThat(ContextUsageEstimator.calculateContextTokens(total(17))).isEqualTo(17);
    }

    @Test
    void calculateFallsBackToPartsWhenTotalIsZero() {
        // pi 的 `||`：totalTokens 为 0（falsy）⇒ 分项和（compaction.ts:165）。
        assertThat(ContextUsageEstimator.calculateContextTokens(parts(10, 5, 1, 2)))
            .isEqualTo(18);
        assertThat(ContextUsageEstimator.calculateContextTokens(parts(0, 0, 0, 0)))
            .isEqualTo(0);
    }

    // ── estimateContextTokens ─────────────────────────────────

    @Test
    void withoutAnyValidUsageEveryMessageIsCharEstimated() {
        var messages = List.of(user("x".repeat(4_000)), assistant("ok", "stop", null));
        var estimate = ContextUsageEstimator.estimateContextTokens(messages);
        assertThat(estimate.lastUsageIndex()).isNull();
        assertThat(estimate.usageTokens()).isEqualTo(0);
        assertThat(estimate.tokens()).isEqualTo(1_001); // 1000 + ceil(2/4)
        assertThat(estimate.trailingTokens()).isEqualTo(1_001);
    }

    @Test
    void lastValidUsageAnchorsTheEstimateAndTrailingCharsAddOn() {
        var messages = List.of(
            user("ignored-before-usage-usage-counts-it"),
            assistant("answer", "stop", total(100)),
            user("abcd")); // ceil(4/4) = 1
        var estimate = ContextUsageEstimator.estimateContextTokens(messages);
        assertThat(estimate.lastUsageIndex()).isEqualTo(1);
        assertThat(estimate.usageTokens()).isEqualTo(100);
        assertThat(estimate.trailingTokens()).isEqualTo(1);
        assertThat(estimate.tokens()).isEqualTo(101);
    }

    @Test
    void abortedErrorAndZeroUsagesNeverAnchor() {
        // pi getAssistantUsage（:167-180）：stopReason ∈ {aborted,error} 或
        // 折算值 ≤ 0 的 assistant **不供用量**，扫描继续向更早的消息找。
        var messages = List.of(
            assistant("anchor", "stop", total(60)),
            assistant("z", "stop", parts(0, 0, 0, 0)), // 折算 0 ⇒ 无效
            user("mid"),
            assistant("err", "error", total(999)),      // error ⇒ 跳过
            assistant("ab", "aborted", total(999)));    // aborted ⇒ 跳过
        var estimate = ContextUsageEstimator.estimateContextTokens(messages);
        assertThat(estimate.lastUsageIndex()).isEqualTo(0);
        assertThat(estimate.usageTokens()).isEqualTo(60);
        // 其后所有消息（含被跳过的 assistant）都按字符估算计入 trailing。
        assertThat(estimate.trailingTokens()).isEqualTo(1 + 1 + 1 + 1);
        assertThat(estimate.tokens()).isEqualTo(64);
    }

    // ── estimateTokens per role ───────────────────────────────

    @Test
    void userTextCeilsByFour() {
        assertThat(ContextUsageEstimator.estimateTokens(user("abc"))).isEqualTo(1);
        assertThat(ContextUsageEstimator.estimateTokens(user("abcd"))).isEqualTo(1);
        assertThat(ContextUsageEstimator.estimateTokens(user("abcde"))).isEqualTo(2);
    }

    @Test
    void imagesCountTheConstantInBothDialects() {
        // pi ESTIMATED_IMAGE_CHARS=4800 ⇒ 1200 tokens；UrlImageContent 是
        // pi-java 方言，取同一常数（ContextUsageEstimator 类注释）。
        var inline = new Message.UserMessage(List.of(
            new ContentBlock.ImageContent("image/png", "AAAA")));
        var url = new Message.UserMessage(List.of(
            new ContentBlock.UrlImageContent("https://ex.com/a.png")));
        assertThat(ContextUsageEstimator.estimateTokens(inline)).isEqualTo(1_200);
        assertThat(ContextUsageEstimator.estimateTokens(url)).isEqualTo(1_200);
        var both = new Message.UserMessage(List.<ContentBlock>of(
            new ContentBlock.TextContent("abcd"), new ContentBlock.ImageContent("image/png", "AAAA")));
        assertThat(ContextUsageEstimator.estimateTokens(both)).isEqualTo(1_201);
    }

    @Test
    void assistantCountsThinkingAndToolCallNamePlusStringifiedArgs() {
        var thinking = new Message.AssistantMessage(
            List.of(new ContentBlock.ThinkingContent("word")), "stop", null,
            null, null, null, null, null, null);
        assertThat(ContextUsageEstimator.estimateTokens(thinking)).isEqualTo(1); // ceil(4/4)

        // pi :288：name.length + JSON.stringify(arguments).length
        var call = new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("id-1", "echo", Map.of("a", "b"))),
            "tool_use", null, null, null, null, null, null, null);
        assertThat(ContextUsageEstimator.estimateTokens(call))
            .isEqualTo((int) Math.ceil((4 + "{\"a\":\"b\"}".length()) / 4.0)); // ceil(13/4)=4
    }

    @Test
    void emptyArgumentsStringifyToBracesLikePi() {
        // Java 方言里 arguments **不可能为 null**（ToolUseContent 的 Map.copyOf
        // 直接拒绝），pi 的 `?? "undefined"` 回退在本构造面上不可达 —— 保留在
        // 实现里纯属形状对齐。这里钉可达形状：空参数 ⇒ "{}"。
        var call = new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("id-1", "x", Map.of())),
            "tool_use", null, null, null, null, null, null, null);
        assertThat(ContextUsageEstimator.estimateTokens(call))
            .isEqualTo((int) Math.ceil((1 + "{}".length()) / 4.0)); // ceil(3/4)=1
    }

    @Test
    void nestedNullValuesStillStringifyLikePi() {
        // 顶层值非 null 即可携带嵌套 null（Map.copyOf 只查顶层）：
        // JSON.stringify 把 null 写成字面量 "null"，长度按串算。
        var args = Map.<String, Object>of("l", java.util.Arrays.asList(null, "a"));
        var call = new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("id-1", "e", args)),
            "tool_use", null, null, null, null, null, null, null);
        assertThat(ContextUsageEstimator.estimateTokens(call))
            .isEqualTo((int) Math.ceil((1 + "{\"l\":[null,\"a\"]}".length()) / 4.0));
    }

    @Test
    void unserializableArgumentsFallBackToSentinel() {
        var cyclic = new LinkedHashMap<String, Object>();
        cyclic.put("self", cyclic); // Jackson 序列化抛错
        var call = new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("id-1", "e", cyclic)),
            "tool_use", null, null, null, null, null, null, null);
        assertThat(ContextUsageEstimator.estimateTokens(call))
            .isEqualTo((int) Math.ceil((1 + "[unserializable]".length()) / 4.0)); // ceil(17/4)=5
    }

    @Test
    void toolResultCountsTextAndImagesLikeUser() {
        var result = new Message.ToolResultMessage("id-1", "echo",
            List.<ContentBlock>of(new ContentBlock.TextContent("abcdefgh"),
                new ContentBlock.ImageContent("image/png", "AAAA")), false);
        assertThat(ContextUsageEstimator.estimateTokens(result)).isEqualTo(1_202);
    }

    // ── shouldCompact ─────────────────────────────────────────

    @Test
    void shouldCompactIsEnabledGatedAndStrictlyAboveWindowMinusReserve() {
        var settings = new CompactionSettings(true, 10, 20_000);
        assertThat(ContextUsageEstimator.shouldCompact(190, 200, settings)).isFalse();
        assertThat(ContextUsageEstimator.shouldCompact(191, 200, settings)).isTrue();
        assertThat(ContextUsageEstimator.shouldCompact(999_999, 200,
            new CompactionSettings(false, 10, 20_000))).isFalse();
    }

    @Test
    void synthesizedPartsUsageStillAnchorsViaTotal() {
        // 3a 合成的终局用量（total=input+output）直接命中 totalTokens 分支。
        var synthesized = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("ok")), "stop", null,
            null, null, null,
            new Usage(3, 4, 0, 0, null, null, 7, Usage.Cost.zero()), null, null);
        var estimate = ContextUsageEstimator.estimateContextTokens(List.of(synthesized));
        assertThat(estimate.tokens()).isEqualTo(7);
        assertThat(estimate.lastUsageIndex()).isEqualTo(0);
    }
}
