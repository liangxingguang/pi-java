package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包②（docs/31 §8.34）：thinking / text 的**请求侧重放规则** —— 线格这一层。
 *
 * <p>pi 的规则分两层：闸（`transform-messages.ts:99-116`，决定块活不活）
 * 与落线（`anthropic-messages.ts:1281-1321`，决定线格长什么样）。
 * 本类钉**落线**；闸那一层在 {@code TransformMessagesTest}。</p>
 *
 * <p>⚠️ 为什么两层分开钉（§8.34.10-4 实测）：在 Anthropic 车道上，闸的四条
 * **非 redacted** 分支里有两条产出的线格与「跳过闸」完全相同（结构性惰性）——
 * 拿线格去钉闸，测的是适配器早就做对的事。</p>
 *
 * <p><b>每条夹具一个断言</b>：断言统一走 {@link #render}，把出参压成
 * {@code 类型:载荷} 的字符串列表 —— 一条断言即覆盖「有没有这块 / 是哪一类 / 载荷对不对」，
 * 避免包① 那种「第二条断言的红被第一条挡住」的掩蔽。</p>
 *
 * <p>⚠️ 标 <b>回归门</b> 的夹具**今天就绿**，它们不是 RE 证据，而是钉住
 * 「本次改动不许把 pi 本来就对的地方改坏」。红灯数字里不含它们。</p>
 */
class AnthropicThinkingReplayTest {

    private static final ModelId<?> TARGET =
        ModelId.of("anthropic", "claude-sonnet-5");

    private MessageCreateParams buildParams(StreamRequest request) throws Exception {
        var options = new ApiOptions(
            "https://api.teamorouter.cn", "sk-test",
            Duration.ofSeconds(10), 1, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        Method method = AnthropicMessagesApi.class.getDeclaredMethod(
            "buildParams", StreamRequest.class);
        method.setAccessible(true);
        return (MessageCreateParams) method.invoke(api, request);
    }

    /** An assistant message whose identity matches {@link #TARGET} (same-model replay). */
    private static Message.AssistantMessage sameModel(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "end_turn", null,
            "anthropic-messages", TARGET.provider(), TARGET.modelName(),
            null, null, null);
    }

    /** An assistant message stamped by a different provider/model. */
    private static Message.AssistantMessage foreignModel(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "end_turn", null,
            "anthropic-messages", "teamorouter", "deepseek-v4-flash",
            null, null, null);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static StreamRequest request(List<Message> messages) {
        return new StreamRequest(TARGET, null, messages, List.of(), 100, 0.5, Map.of());
    }

    /**
     * 出参压成 {@code 类型:载荷}；每种块的判别字段都取到，一条断言即可比对整条线格。
     *
     * <p>⚠️ 走的是**出参里全部消息的全部块**（不只助手车道）—— 所以凡带了
     * {@code user("hi")} 的夹具，期望值**必须以 {@code "text:hi"} 开头**。
     * 第一版漏了这条前缀，12 条夹具全红、且**红的原因与本次要修的行为无关**
     * （§8.34.5 记为新形态：夹具的**观测面比被测车道宽** ⇒ 期望值系统性错位）。</p>
     */
    private static List<String> render(MessageCreateParams params) {
        var out = new ArrayList<String>();
        for (var message : params.messages()) {
            for (var b : message.content().asBlockParams()) {
                if (b.isText()) {
                    out.add("text:" + b.asText().text());
                } else if (b.isThinking()) {
                    out.add("thinking:" + b.asThinking().signature());
                } else if (b.isRedactedThinking()) {
                    out.add("redacted:" + b.asRedactedThinking().data());
                } else if (b.isToolUse()) {
                    out.add("tool_use:" + b.asToolUse().name());
                } else {
                    out.add("other");
                }
            }
        }
        return out;
    }

    private List<String> wire(List<Message> messages) throws Exception {
        return render(buildParams(request(messages)));
    }

    /** The placeholder text pi puts in a redacted block (anthropic-messages.ts:641). */
    private static final String REDACTED_PLACEHOLDER = "[Reasoning redacted]";

    // ---------------------------------------------------------------- RED（6 条）

    /**
     * <b>B13 / P2-a2</b>（同模型 + redacted）：线格必须是 {@code redacted_thinking}，
     * 载荷进 {@code data}。
     *
     * <p>pi `anthropic-messages.ts:1287-1294` 走的是**第一条**分支：`block.redacted`
     * ⇒ `{type:"redacted_thinking", data: block.thinkingSignature}`。而 pi-java 的
     * `redacted()` 在生产代码里**零读点** ⇒ 该块落进「有签名」分支，被当成
     * **带签名的 thinking 块**送出去，签名位放的是**不透明密文**。</p>
     */
    @Test
    void redactedSameModelReplaysAsRedactedThinking() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            sameModel(new ContentBlock.ThinkingContent(
                REDACTED_PLACEHOLDER, "opaque-payload", true))));

        assertThat(actual).containsExactly("text:hi", "redacted:opaque-payload");
    }

    /**
     * <b>P2-a1</b>（跨模型 + redacted）：整块**丢弃**。
     *
     * <p>pi `transform-messages.ts:102-105` 给了理由：不透明密文
     * 「only valid for the same model」，跨模型送出去只会换来 API 错误。
     * 注意 pi 这里**没有**降级为 text 的分支 —— 是**丢**（§8.34.4 决策 4）。
     * 密文也不许从 text 漏出去，所以 {@link #render} 的载荷位能一并钉住。</p>
     */
    @Test
    void redactedCrossModelIsDropped() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            foreignModel(new ContentBlock.ThinkingContent(
                REDACTED_PLACEHOLDER, "opaque-payload", true))));

        assertThat(actual).containsExactly("text:hi");
    }

    /**
     * <b>P2 / 闸分支 (e)</b>（跨模型 + 有签名 + 有文本）：降级为 **text**。
     *
     * <p>pi 的闸 `:109` 只在 `isSameModel` 时保留；落到 `:113-116` 返回
     * `{type:"text", text: block.thinking}` —— 签名**连同块一起被扔掉**。
     * pi-java 无闸 ⇒ 带签名的 thinking 块被原样送给另一个模型，
     * Anthropic 会因签名不匹配而报错（这正是 web 那个
     * `` `signature` is not set `` 的同一族）：文本留下、签名不能留。</p>
     */
    @Test
    void signatureWithTextCrossModelDowngradesToText() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            foreignModel(new ContentBlock.ThinkingContent("reasoning body", "sig-abc"))));

        assertThat(actual).containsExactly("text:hi", "text:reasoning body");
    }

    /**
     * <b>P2 / 闸分支 (c)</b>（跨模型 + 有签名 + **空**文本）：**丢弃**。
     *
     * <p>pi 的闸 `:111` `if (!block.thinking || block.thinking.trim() === "") return [];`
     * 在 `isSameModel` 之外**无条件**成立 —— 「空文本的豁免」只给同模型。
     * 这条与上一条**只差文本是否为空**，却必须分开钉（包① 的掩蔽教训）。</p>
     */
    @Test
    void signatureWithBlankTextCrossModelIsDropped() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            foreignModel(new ContentBlock.ThinkingContent("", "sig-abc"))));

        assertThat(actual).containsExactly("text:hi");
    }

    /**
     * <b>P3-1</b>（助手车道）：空白的 text 块不许发出去。
     *
     * <p>pi `anthropic-messages.ts:1282` 在助手内容循环的第一个 `if` 里
     * `if (block.text.trim().length === 0) continue;`；pi-java 的 `toBlockParams`
     * 无条件 `ofText`（`:284-286`）。`:238` 护的是**消息**不是**块**。</p>
     */
    @Test
    void blankAssistantTextBlockIsNotSent() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            sameModel(
                new ContentBlock.TextContent(""),
                new ContentBlock.TextContent("   "),
                new ContentBlock.TextContent("real answer"))));

        assertThat(actual).containsExactly("text:hi", "text:real answer");
    }

    /**
     * <b>P3-3</b>（用户车道）：pi 对**用户**车道同样过滤空白 text 块
     * （`anthropic-messages.ts:1262-1268` 的 `filteredBlocks`，全空则
     * `:1269 continue` 整条消息不发）。pi-java 的 `toBlockParams` 用户/助手共用
     * ⇒ 修一处两条车道同时对齐（`:238` 已提供「整条消息不发」那一半）。</p>
     */
    @Test
    void blankUserTextBlockIsNotSent() throws Exception {
        var actual = wire(List.of(
            new Message.UserMessage(List.of(
                new ContentBlock.TextContent("   "),
                new ContentBlock.TextContent("hi")))));

        assertThat(actual).containsExactly("text:hi");
    }

    // ------------------------------------------------- 回归门（今天就绿，不计红灯）

    /**
     * <b>回归门</b>（同模型 + 有签名 + 空文本）：仍是 thinking 块，**文本可空**。
     *
     * <p>pi 闸 `:109` 的注释明写理由：same model 且带签名就要保留，
     * 「even if the thinking text is empty (OpenAI encrypted reasoning)」。
     * pi-java 现在靠 `:315` 的 `&& signature.isEmpty()` 恰好做对了 ——
     * 加闸时**不许**把它改坏。</p>
     */
    @Test
    void signatureWithBlankTextSameModelKeepsThinking() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            sameModel(new ContentBlock.ThinkingContent("", "sig-abc"))));

        assertThat(actual).containsExactly("text:hi", "thinking:sig-abc");
    }

    /**
     * <b>回归门</b>（同模型 + 有签名 + 有文本）：thinking 块带签名原样送出。</p>
     */
    @Test
    void signatureWithTextSameModelKeepsThinking() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            sameModel(new ContentBlock.ThinkingContent("reasoning body", "sig-abc"))));

        assertThat(actual).containsExactly("text:hi", "thinking:sig-abc");
    }

    /**
     * <b>回归门</b>（同模型 + **无**签名 + 有文本）：降级为 text。
     *
     * <p>这条是 B8 的**对照面**：pi 的落线 `:1296-1316` 在 `!hasThinkingSignature`
     * 时按 `allowEmptySignature` 二选一，而该键**缺省 false**（`:193` 的 `?? false`）
     * ⇒ **没有** compat 标记的模型上，pi 也是降级 text。pi-java 现状与之一致
     * （§8.34.4 决策 3「两态」的行为证据）。</p>
     */
    @Test
    void noSignatureSameModelDowngradesToText() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            sameModel(new ContentBlock.ThinkingContent("reasoning body"))));

        assertThat(actual).containsExactly("text:hi", "text:reasoning body");
    }

    /**
     * <b>回归门</b>（同模型 + 无签名 + 空文本）：丢。
     *
     * <p>pi 闸 `:111`；pi-java `:315` —— 两侧同判。</p>
     */
    @Test
    void blankThinkingWithoutSignatureIsDropped() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            sameModel(new ContentBlock.ThinkingContent(""))));

        assertThat(actual).containsExactly("text:hi");
    }

    /**
     * <b>回归门</b>（跨模型 + 无签名 + 空文本）：同样丢（先于跨模型降级判定）。</p>
     */
    @Test
    void blankThinkingWithoutSignatureCrossModelIsDropped() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            foreignModel(new ContentBlock.ThinkingContent(""))));

        assertThat(actual).containsExactly("text:hi");
    }

    /**
     * <b>回归门</b>（同模型 + **纯空白**签名 + 空文本）：丢。
     *
     * <p>这条钉住 §8.34.6 证伪点 2 的**结论**：pi 的签名判定在两处口径不一致 ——
     * 闸 `:109` 用**真值**（`"   "` 为真 ⇒ 保留），落线 `:1297` 用 `trim` 复核
     * （⇒ `hasThinkingSignature` 为假）。但落线 `:1298`
     * `if (block.thinking.trim().length === 0 && !hasThinkingSignature) continue;`
     * 又用 trim 判文本 ⇒ 纯空白签名 + 空文本**仍然被丢**。
     * <b>该不对称在出参上不可观察</b>（凡会分歧的路径都被 `:1298`/`:1304` 重新归并），
     * 故**不照抄**：pi-java 保持 `:313` 的 trim 口径（更简单、行为相同）。</p>
     */
    @Test
    void whitespaceOnlySignatureWithBlankTextIsDropped() throws Exception {
        var actual = wire(List.of(
            user("hi"),
            sameModel(new ContentBlock.ThinkingContent("", "   "))));

        assertThat(actual).containsExactly("text:hi");
    }
}
