package com.pijava.ai.utils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-10：{@link Estimate} —— pi {@code ai/src/utils/estimate.ts} 的逐条移植。
 *
 * <p>本类的消费方是 {@code clampMaxTokensToContext}（{@code 原 docs/57 §1.1}），
 * 所以这里的每条断言都在给「{@code max_tokens} 夹到哪里」定值。</p>
 *
 * <p>⚠️ 三条最容易被写错的：① {@code totalTokens} 用的是 {@code ||} 语义（**0 要落到求和**）；
 * ② 图片按 <b>4800 字符</b>折（不是按字节、不是按分辨率）；③ 用量锚的**时间戳守卫**是
 * pi 独有的正向扫描语义（{@code estimate.ts:71-95}），反向取末条那份在
 * {@code agent-core.ContextUsageEstimator} 里，不在这里 —— 见
 * {@link Estimate} 类 javadoc 的对照表。</p>
 */
class EstimateTest {

    // ── calculateContextTokens（pi estimate.ts:18-20）────────────────────

    /** {@code totalTokens} 非零 ⇒ **就是它**，不求和（provider 报的总量优先）。 */
    @Test
    void nonZeroTotalTokensWinsOverTheSum() {
        assertThat(Estimate.calculateContextTokens(usage(10, 5, 0, 0, 77))).isEqualTo(77);
    }

    /** ⚠️ pi 用的是 {@code ||}：{@code totalTokens === 0} ⇒ **落到求和**（不是取 0）。 */
    @Test
    void zeroTotalTokensFallsBackToTheSum() {
        assertThat(Estimate.calculateContextTokens(usage(10, 5, 2, 3, 0))).isEqualTo(20);
    }

    // ── estimateMessageTokens（pi estimate.ts:46-69）────────────────────

    /** 文本按 {@code ceil(chars / 4)}：11 字符 ⇒ 3。 */
    @Test
    void textOnlyContentUsesCeilOfCharsOverFour() {
        assertThat(Estimate.estimateMessageTokens(user("hello world"))).isEqualTo(3);
    }

    /** ⚠️ 图片恒折 4800 字符 ⇒ 1200 token，与图片本身多大无关。 */
    @Test
    void imageBlockCountsAsFortyEightHundredChars() {
        var message = new Message.UserMessage(List.of(
            new ContentBlock.ImageContent("image/png", "AAAA")));
        assertThat(Estimate.estimateMessageTokens(message)).isEqualTo(1200);
    }

    /** 文本块与图片块**相加**（pi 的 {@code estimateTextAndImageContentChars} 逐块累加）。 */
    @Test
    void textAndImageInOneMessageAddUp() {
        var message = new Message.UserMessage(List.of(
            new ContentBlock.TextContent("abcd"),
            new ContentBlock.ImageContent("image/png", "AAAA")));
        assertThat(Estimate.estimateMessageTokens(message)).isEqualTo(1 + 1200);
    }

    /** 工具结果走的是同一条「文本＋图片」路（pi 的 {@code toolResult} 分支）。 */
    @Test
    void toolResultUsesTheTextAndImagePath() {
        var message = new Message.ToolResultMessage("c1", "ls",
            List.of(new ContentBlock.TextContent("abcd")), false);
        assertThat(Estimate.estimateMessageTokens(message)).isEqualTo(1);
    }

    /** 助手消息：{@code text} ＋ {@code thinking} ＋ {@code name + JSON(args)}，一起除 4 取整。 */
    @Test
    void assistantCountsTextThinkingAndToolCallArguments() {
        // 4 + 4 + (2 + 10) = 20 ⇒ ceil(20/4) = 5
        var message = assistantMessage(Instant.ofEpochMilli(1), null,
            new ContentBlock.TextContent("abcd"),
            new ContentBlock.ThinkingContent("efgh", null, false),
            new ContentBlock.ToolUseContent("c1", "ls", Map.of("q", "hi")));
        assertThat(Estimate.estimateMessageTokens(message)).isEqualTo(5);
    }

    /** 系统消息：提示文本 ＋ 每个 section（保序、{@code \n\n} 拼）＋ 工具增删的 JSON。 */
    @Test
    void systemMessageCountsTextSectionsAndToolDeltas() {
        var bare = new Message.SystemMessage("abcd", null, null, null, null);
        assertThat(Estimate.estimateMessageTokens(bare)).isEqualTo(1);

        var withSection = new Message.SystemMessage("abcd", null, Map.of("rules", "efgh"), null, null);
        // "abcd\n\nefgh" = 10 字符 ⇒ 3
        assertThat(Estimate.estimateMessageTokens(withSection)).isEqualTo(3);

        var added = new ToolDefinition("ls", "list", Map.of("type", "object"));
        var withTools = new Message.SystemMessage("abcd", null, null,
            List.of(added), List.of(new ToolReference("rm")));
        var expectedTools = Estimate.estimateTextTokens(Estimate.safeJsonStringify(List.of(added)))
            + Estimate.estimateTextTokens(Estimate.safeJsonStringify(List.of(new ToolReference("rm"))));
        assertThat(Estimate.estimateMessageTokens(withTools)).isEqualTo(1 + expectedTools);
    }

    // ── safeJsonStringify（pi estimate.ts:22-28）────────────────────────

    /**
     * ⚠️ 整数值的浮点必须序列化成 JS 的样子：{@code 1.0} ⇒ {@code 1}。
     *
     * <p>工具 schema 里 {@code maximum}/{@code minimum} 这类整数值极常见，不归一就会
     * 把每个数字多算两个字符 ⇒ 夹出来的 {@code max_tokens} 偏小。这条钉的就是那个归一。</p>
     */
    @Test
    void integralNumbersSerializeLikeJavaScript() {
        assertThat(Estimate.safeJsonStringify(Map.of("maximum", 1.0))).isEqualTo("{\"maximum\":1}");
        assertThat(Estimate.safeJsonStringify(List.of(Map.of("n", 2.0f)))).isEqualTo("[{\"n\":2}]");
        // 非整数**不动**（JS 也同样输出 0.5）
        assertThat(Estimate.safeJsonStringify(Map.of("p", 0.5))).isEqualTo("{\"p\":0.5}");
    }

    /** 序列化不了的值退化成一个固定串，**不抛** —— 估算器不该让请求构建失败。 */
    @Test
    void unserializableValuesDegradeInsteadOfThrowing() {
        assertThat(Estimate.safeJsonStringify(Map.of("bad", new Object())))
            .isEqualTo("[unserializable]");
    }

    /** java 的 {@code null} ≙ pi 的 {@code undefined}（{@code JSON.stringify(null)} 是另一回事）。 */
    @Test
    void nullSerializesAsUndefined() {
        assertThat(Estimate.safeJsonStringify(null)).isEqualTo("undefined");
    }

    // ── estimateContextTokens（pi estimate.ts:97-112）───────────────────

    /** 命中用量锚 ⇒ {@code usage + 其后逐条估算}，且索引一并报出。 */
    @Test
    void usageAnchorAddsTheTrailingEstimate() {
        var estimate = Estimate.estimateContextTokens(List.of(
            assistantMessage(Instant.ofEpochMilli(1), usage(10, 5, 0, 0, 15),
                new ContentBlock.TextContent("abcd")),
            user("abcd")));

        assertThat(estimate.usageTokens()).isEqualTo(15);
        assertThat(estimate.trailingTokens()).isEqualTo(1);
        assertThat(estimate.tokens()).isEqualTo(16);
        assertThat(estimate.lastUsageIndex()).isEqualTo(0);
    }

    /** 没有可用锚 ⇒ 全表逐条估算，{@code usageTokens} 为 0、尾量等于全量。 */
    @Test
    void withoutAnAnchorEveryMessageIsEstimated() {
        var estimate = Estimate.estimateContextTokens(List.of(user("abcd"), user("abcd")));

        assertThat(estimate.usageTokens()).isZero();
        assertThat(estimate.trailingTokens()).isEqualTo(2);
        assertThat(estimate.tokens()).isEqualTo(2);
        assertThat(estimate.lastUsageIndex()).isNull();
    }

    /** {@code TranscriptContext} 入口与裸表入口同值（pi 的 {@code "messages" in context} 判别）。 */
    @Test
    void transcriptContextEntryMatchesTheBareListEntry() {
        var messages = List.of(user("abcd"), user("abcd"));
        assertThat(Estimate.estimateContextTokens(new TranscriptContext(messages)))
            .isEqualTo(Estimate.estimateContextTokens(messages));
    }

    // ── ★ 时间戳守卫（pi estimate.ts:71-95，本包与 agent-core 那份的分叉）──

    /**
     * ★ 前缀更新过的旧锚**必须被拒**：{@code estimate.ts:79-80} 的守卫。
     *
     * <p>表里两条助手消息的**时间戳反序**（模拟「压缩摘要插在响应之后」）⇒ 正向扫描
     * 收到的是**时间戳更大的那条**（下标 1），不是最后一条（下标 2）。
     * ⚠️ 反向取末条那份（{@code agent-core.ContextUsageEstimator}）在这里会选下标 2
     * —— 这就是两份估算器的**唯一语义分叉**，本条把它钉死。</p>
     */
    @Test
    void aStaleAnchorIsRejectedByTheTimestampGuard() {
        var fresh = assistantMessage(Instant.ofEpochMilli(300), usage(10, 5, 0, 0, 15),
            new ContentBlock.TextContent("abcd"));
        var stale = assistantMessage(Instant.ofEpochMilli(100), usage(10, 5, 0, 0, 999),
            new ContentBlock.TextContent("abcd"));

        var estimate = Estimate.estimateContextTokens(List.of(fresh, stale));

        assertThat(estimate.lastUsageIndex())
            .as("时间戳 100 的锚早于前缀里的 300 ⇒ 必须被拒")
            .isEqualTo(0);
        assertThat(estimate.usageTokens()).isEqualTo(15);
        assertThat(estimate.tokens()).isEqualTo(15 + 1);
    }

    /** 被中止／出错的回合不当作量锚（pi 的 {@code stopReason !== "aborted" && !== "error"}）。 */
    @Test
    void abortedAndErroredAssistantsAreNotAnchors() {
        var aborted = assistantMessage(Instant.ofEpochMilli(1), usage(10, 5, 0, 0, 15),
            "aborted", new ContentBlock.TextContent("abcd"));
        var errored = assistantMessage(Instant.ofEpochMilli(2), usage(10, 5, 0, 0, 15),
            "error", new ContentBlock.TextContent("abcd"));

        var estimate = Estimate.estimateContextTokens(List.of(aborted, errored));

        assertThat(estimate.lastUsageIndex()).isNull();
        assertThat(estimate.usageTokens()).isZero();
    }

    /** 用量为零的回合也不是锚（{@code calculateContextTokens(usage) > 0} 那道门）。 */
    @Test
    void zeroUsageIsNotAnAnchor() {
        var zero = assistantMessage(Instant.ofEpochMilli(1), usage(0, 0, 0, 0, 0),
            new ContentBlock.TextContent("abcd"));

        var estimate = Estimate.estimateContextTokens(List.of(zero));

        assertThat(estimate.lastUsageIndex()).isNull();
        assertThat(estimate.tokens()).isEqualTo(1);
    }

    // ── 原 docs/71 G1：user/toolResult 的时间戳也推进前缀守卫 ──────────────

    /**
     * pi {@code estimate.ts:91} 的推进对**每一条**消息生效（它的四个变体都有 timestamp）。
     * 用户消息的时间戳晚于那条 assistant ⇒ 那条的 usage 描述不了含它的前缀 ⇒ 锚不成立。
     */
    @Test
    void userMessageTimestampAdvancesThePrefixGuard() {
        var earlier = Instant.parse("2026-01-01T00:00:00Z");
        var later = Instant.parse("2026-01-02T00:00:00Z");

        var estimate = Estimate.estimateContextTokens(List.of(
            user("hi", later),
            assistantMessage(earlier, usage(100, 10, 0, 0, 110),
                new ContentBlock.TextContent("a"))));

        assertThat(estimate.lastUsageIndex()).isNull();
        assertThat(estimate.usageTokens()).isZero();
    }

    @Test
    void toolResultMessageTimestampAdvancesThePrefixGuard() {
        var earlier = Instant.parse("2026-01-01T00:00:00Z");
        var later = Instant.parse("2026-01-02T00:00:00Z");

        var estimate = Estimate.estimateContextTokens(List.of(
            new Message.ToolResultMessage("call_1", "bash",
                List.<ContentBlock>of(new ContentBlock.TextContent("out")),
                null, null, List.<String>of(), false, later),
            assistantMessage(earlier, usage(100, 10, 0, 0, 110),
                new ContentBlock.TextContent("a"))));

        assertThat(estimate.lastUsageIndex()).isNull();
    }

    /** 对照：user 时间戳**早于** assistant ⇒ 守卫不拦，锚仍成立。 */
    @Test
    void olderUserTimestampKeepsTheAnchor() {
        var earlier = Instant.parse("2026-01-01T00:00:00Z");
        var later = Instant.parse("2026-01-02T00:00:00Z");

        var estimate = Estimate.estimateContextTokens(List.of(
            user("hi", earlier),
            assistantMessage(later, usage(100, 10, 0, 0, 110),
                new ContentBlock.TextContent("a"))));

        assertThat(estimate.lastUsageIndex()).isEqualTo(1);
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private static Usage usage(double input, double output, double cacheRead,
                               double cacheWrite, double total) {
        return new Usage(input, output, cacheRead, cacheWrite, null, null, total, Usage.Cost.zero());
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message user(String text, Instant timestamp) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)), timestamp);
    }

    private static Message.AssistantMessage assistantMessage(Instant timestamp, Usage usage,
                                                             ContentBlock... blocks) {
        return assistantMessage(timestamp, usage, "stop", blocks);
    }

    private static Message.AssistantMessage assistantMessage(Instant timestamp, Usage usage,
                                                             String stopReason, ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), stopReason, null,
            "anthropic-messages", "anthropic", "claude-sonnet-5", usage, timestamp, null, null);
    }
}
