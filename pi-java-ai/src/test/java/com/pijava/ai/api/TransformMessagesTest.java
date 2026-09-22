package com.pijava.ai.api;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包②（docs/31 §8.34）：{@link TransformMessages} —— **闸**这一层。
 *
 * <p>与 {@code AnthropicThinkingReplayTest}（钉**落线**）分工：pi 的规则是两层的 ——
 * 闸决定块活不活（`transform-messages.ts:99-116`）、落线决定线格长什么样
 * （`anthropic-messages.ts:1281-1321`）。</p>
 *
 * <p><b>为什么闸必须有自己的一组夹具</b>（§8.34.5 第三条规则）：在 Anthropic 车道上，
 * 闸的非 redacted 分支里有两条产出的**线格与「跳过闸」完全相同**（结构性惰性）——
 * 只钉线格的话，「规则放在适配器里」与「规则放在共享通道里」这两种实现**都绿**，
 * 于是 决策 1 想钉的「**位置**」根本没被钉住。这组夹具直接断言 `apply()` 的返回值，
 * 使「规则住在哪一层」成为可测事实。</p>
 *
 * <p>⚠️ 标 <b>回归门</b> 的是今天（空过版）就绿的：它们钉的是「闸非 assistant 的消息
 * 一律原样放行」，不属于本次要修的缺口，故**不计入红灯数**。</p>
 *
 * <p><b>实测：`Tests run: 9, Failures: 4`</b> —— 其中 3 条是**行为**红灯（跨模型有文本→text /
 * 跨模型空文本→丢 / 跨模型 redacted→丢），第 4 条 `thinkingWithNeitherTextNorSignatureIsDropped`
 * 是**位置**红灯（线格早已正确，只是「丢弃」这个动作住在适配器 `:315-317` 里而不是闸里）——
 * 两类都在各自 javadoc 里标明。余下 5 条为回归门。</p>
 */
class TransformMessagesTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");
    private static final String API = "anthropic-messages";

    /**
     * 闸的第四形参（包 H2 新增）—— 本文件全部用例都喂**视觉模型**，让图片闸恒不触发：
     * 这里钉的是 thinking 五分支，不是图片降级（那组在 {@code TransformMessagesImageDowngradeTest}）。
     */
    private static final ModelInfo VISION_TARGET = new ModelInfo(
        TARGET, "Claude Sonnet 5",
        java.util.Set.of(com.pijava.ai.model.ModelCapability.TEXT,
            com.pijava.ai.model.ModelCapability.IMAGE_INPUT),
        200_000, 8_192, false, com.pijava.ai.model.PricingInfo.UNKNOWN);

    private static Message.AssistantMessage sameModel(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            API, TARGET.provider(), TARGET.modelName(), null, null, null, null);
    }

    private static Message.AssistantMessage foreignModel(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            API, "teamorouter", "deepseek-v4-flash", null, null, null, null);
    }

    private static List<Message> applied(List<Message> messages) {
        return TransformMessages.apply(messages, TARGET, API, VISION_TARGET);
    }

    /** 只取第一条消息的内容块，压成 {@code 类型:载荷} —— 与线格夹具同一套词汇。 */
    private static List<String> content(Message message) {
        var out = new ArrayList<String>();
        if (!(message instanceof Message.AssistantMessage a)) {
            return out;
        }
        for (var block : a.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                out.add("text:" + tc.text());
            } else if (block instanceof ContentBlock.ThinkingContent th) {
                out.add(th.redacted()
                    ? "redacted:" + th.signature()
                    : "thinking:" + th.signature());
            } else if (block instanceof ContentBlock.ToolUseContent tu) {
                out.add("tool_use:" + tu.name());
            } else {
                out.add("other");
            }
        }
        return out;
    }

    private static List<String> gated(Message message) {
        return content(applied(List.of(message)).get(0));
    }

    // ------------------------------------------------------------------ RED

    /**
     * <b>闸 (e)</b> 跨模型 + 有签名 + 有文本 ⇒ 降级为 **text**（`transform-messages.ts:113-116`
     * 的 `{type:"text", text: block.thinking}`）。
     *
     * <p>这条同时钉**位置**：同一条线格也能由适配器里的 `:318-322` 产出，
     * 所以只钉线格的话「规则在适配器里」也绿。</p>
     */
    @Test
    void crossModelThinkingWithTextBecomesTextBlock() {
        assertThat(gated(foreignModel(new ContentBlock.ThinkingContent(
            "reasoning body", "sig-abc"))))
            .containsExactly("text:reasoning body");
    }

    /**
     * <b>闸 (c)</b> 跨模型 + 有签名 + **空文本** ⇒ **丢**（`:111`，同模型之外的豁免不存在）。
     */
    @Test
    void crossModelThinkingWithBlankTextIsDropped() {
        assertThat(gated(foreignModel(new ContentBlock.ThinkingContent("", "sig-abc"))))
            .isEmpty();
    }

    /**
     * <b>闸 (a)</b> 跨模型 + redacted ⇒ **丢**（`:102-105`，无降级分支 —— 与上一条的区别是
     * 它连文本都不留，理由在 pi 的注释里：不透明密文 only valid for the same model）。
     */
    @Test
    void redactedThinkingIsDroppedForAnotherModel() {
        assertThat(gated(foreignModel(new ContentBlock.ThinkingContent(
            "[Reasoning redacted]", "opaque-payload", true))))
            .isEmpty();
    }

    // ----------------------------------------------------------- 回归门（今天就绿）

    /**
     * <b>回归门</b> 同模型 + 有签名 + **空文本** ⇒ 保留（`:109` 的注释：same model 且带签名
     * 就要留住，"even if the thinking text is empty"）。
     */
    @Test
    void sameModelThinkingWithSignatureIsKept() {
        assertThat(gated(sameModel(new ContentBlock.ThinkingContent("", "sig-abc"))))
            .containsExactly("thinking:sig-abc");
    }

    /**
     * <b>回归门</b> 同模型 + redacted ⇒ 保留**原块**（redacted 位与载荷都得带着，
     * 落线时才知道该发 `redacted_thinking`）。跨模型的丢在 {@link #redactedThinkingIsDroppedForAnotherModel}。
     */
    @Test
    void redactedThinkingIsKeptForTheSameModel() {
        assertThat(gated(sameModel(new ContentBlock.ThinkingContent(
            "[Reasoning redacted]", "opaque-payload", true))))
            .containsExactly("redacted:opaque-payload");
    }

    /**
     * <b>位置红灯</b>（**不是**行为缺口）无签名且无文本 ⇒ 闸里就该丢掉（`:111`）。
     *
     * <p>⚠️ 这条今天在**闸**这一层是红的，但**线格上早就是对的** —— 丢弃目前由
     * `AnthropicMessagesApi:315-317` 承担。所以它的红证明的是「规则住错了层」，
     * 而不是「用户看得见的行为错了」。**如实计入红灯数（4 条），但不算行为缺口。**</p>
     *
     * <p>pi 在这两处**都有**这个判断（闸 `:111` + 落线 `:1298`），故实现后适配器那侧
     * 保留为**同 pi 的冗余**（闸一旦丢掉，落线那半恒不可达 —— 与 pi 一致）。</p>
     */
    @Test
    void thinkingWithNeitherTextNorSignatureIsDropped() {
        assertThat(gated(sameModel(new ContentBlock.ThinkingContent(""))))
            .isEmpty();
    }

    /**
     * <b>回归门</b> 跨模型的 **text** 块原样留下 —— pi `:117-123` 那条分支写了个
     * `{type:"text", text: block.text}` 的新对象，**与直接返回同形**（结构性惰性）。
     * 留这条夹具是为了把「惰性」写成可测事实，而不是留成注释里的断言。
     */
    @Test
    void crossModelTextBlockSurvivesUnchanged() {
        assertThat(gated(foreignModel(new ContentBlock.TextContent("hello"))))
            .containsExactly("text:hello");
    }

    /** <b>回归门</b> 用户消息**原样放行**（pi `:79-81` 直接 `return msg`）。 */
    @Test
    void userMessagePassesThroughUnchanged() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));

        assertThat(applied(List.of(user))).containsExactly(user);
    }

    /**
     * <b>回归门</b> toolResult 原样放行（pi `:83-92` 只在**有 id 映射时**改，
     * 而 id 归一属 B14、本包不实现 ⇒ 此处恒不变）。
     */
    @Test
    void toolResultMessagePassesThroughUnchanged() {
        Message toolResult = new Message.ToolResultMessage(
            "toolu_1", "read", List.of(new ContentBlock.TextContent("ok")), false);

        assertThat(applied(List.of(toolResult))).containsExactly(toolResult);
    }
}
