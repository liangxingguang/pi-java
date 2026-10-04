package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包B84 步5：Google 车道的<b>助手消息重放</b>两处（pi {@code google-shared.ts:240}／{@code :271}）。
 *
 * <ol>
 *   <li><b>空白文本块跳过</b>（{@code :240}）—— java 照发 {@code {"text":""}}，pi 什么都不发。
 *       ⚠️ 这条**不是理论**：pi 自己的流式侧只要 {@code part.text !== undefined} 就建块
 *       （含空串，{@code google-generative-ai.ts:113}／{@code :141-144}），java 的 Google
 *       车道**同样**（{@code GoogleGenerativeAiApi:187-194} 只判 {@code isPresent()}、
 *       不判空）⇒ 真链路上会造出空文本块并回放出去。</li>
 *   <li><b>{@code functionCall.id} 的门</b>（{@code :271}）—— java 此前**恒发**
 *       {@code id}，pi 只在 {@link GoogleMessageConverter#requiresToolCallId} 为真时发。
 *       ⚠️ 这一条超出 B84 字面的「工具结果路径」（assistant 侧），但它是**同一个谓词的
 *       同一道门**：只关门的一侧会造出 pi 里不存在的状态（functionCall 有 id、
 *       functionResponse 没有）⇒ 一并做（{@code 原 docs/45 D3}）。</li>
 * </ol>
 */
class GoogleAssistantReplayTest {

    private static final ModelId<?> GEMINI_25 = ModelId.of("google", "gemini-2.5-flash");
    private static final ModelId<?> GEMINI_3 = ModelId.of("google", "gemini-3-pro-preview");
    private static final ModelId<?> CLAUDE = ModelId.of("google", "claude-sonnet-4");

    private static ModelInfo vision(ModelId<?> id) {
        return new ModelInfo(id, id.modelName(), Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            128000, 8192, false, PricingInfo.UNKNOWN);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks));
    }

    private static ContentBlock toolCall(String id) {
        return new ContentBlock.ToolUseContent(id, "read", Map.of("path", "a.txt"));
    }

    // ── ① 空白文本块跳过（pi :240）────────────────────────────────────

    /**
     * 空串与纯空白文本块**都不上线**，可见文本照常。
     *
     * <p>pi 的判据是 {@code (!block.text || block.text.trim() === "") && !thoughtSignature}
     * —— 「除非带签名」那半句在 java **恒为假**（{@code TextContent} 没有签名字段，
     * {@code 原 docs/45 D8}）⇒ 实现是「空白就跳」。</p>
     */
    @Test
    void blankAssistantTextBlocksAreSkipped() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistant(new ContentBlock.TextContent(""), new ContentBlock.TextContent("   "),
                new ContentBlock.TextContent("visible"))));

        var parts = GoogleWireBody.parts(contents, 1);
        assertThat(parts).as("两个空白块被跳过，只剩可见文本").hasSize(1);
        assertThat(parts.get(0).path("text").asText()).isEqualTo("visible");
    }

    /**
     * 整条助手消息**只有**空白文本 ⇒ 该回合整个不上线（pi {@code :279}
     * {@code if (parts.length === 0) continue}）。
     */
    @Test
    void assistantWithOnlyBlankTextProducesNoTurn() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"), assistant(new ContentBlock.TextContent(""))));

        assertThat(contents).as("只剩 user 回合").hasSize(1);
        assertThat(contents.get(0).path("role").asText()).isEqualTo("user");
    }

    /**
     * 空白块与工具调用**同处一条消息**：跳过的是那个块，不是整条消息。
     *
     * <p>钉住「跳过」与「整条丢」的边界 —— 把判据写成整条消息级的实现会在这条红。</p>
     */
    @Test
    void blankTextIsSkippedButSiblingToolCallsSurvive() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"), assistant(new ContentBlock.TextContent("  "), toolCall("call_a"))));

        var parts = GoogleWireBody.parts(contents, 1);
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).path("functionCall").path("name").asText()).isEqualTo("read");
    }

    // ── ② functionCall.id 的门（pi :271）──────────────────────────────

    /** gemini-2.5 ⇒ 工具调用**不带** {@code id}（今天恒带）。 */
    @Test
    void gemini2OmitsFunctionCallId() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25),
            List.of(user("hi"), assistant(toolCall("call_a"))));

        var fc = GoogleWireBody.parts(contents, 1).get(0).path("functionCall");
        assertThat(fc.path("name").asText()).as("确实是工具调用").isEqualTo("read");
        assertThat(fc.has("id")).as("2.5 不发 id").isFalse();
    }

    /** gemini-3 ⇒ 带 {@code id}，取值是块上的 id。 */
    @Test
    void gemini3IncludesFunctionCallId() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_3),
            List.of(user("hi"), assistant(toolCall("call_a"))));

        assertThat(GoogleWireBody.parts(contents, 1).get(0).path("functionCall").path("id").asText())
            .isEqualTo("call_a");
    }

    /**
     * ⚠️ {@code claude-*} 经 Google 车道（Cloud Code Assist）**同样要** {@code id} ——
     * 与 gemini-2.5 摆在一起，钉住这道门不是「gemini 版本」一个条件。
     */
    @Test
    void claudeBehindGoogleAlsoRequiresFunctionCallId() throws Exception {
        var contents = GoogleWireBody.contents(vision(CLAUDE),
            List.of(user("hi"), assistant(toolCall("call_a"))));

        assertThat(GoogleWireBody.parts(contents, 1).get(0).path("functionCall").path("id").asText())
            .isEqualTo("call_a");
    }

    // ── Batch F 步 3：thoughtSignature 重放（pi google-shared.ts:228-283）────

    /** 带身份（provider+model）的助手消息，签名才会被重放。 */
    private static Message assistantFrom(String provider, String model, ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), null, null,
            "google-generative-ai", provider, model, null, null, null, null);
    }

    @Test
    void sameModelReplaysTextSignature() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistantFrom("google", "gemini-2.5-flash",
                new ContentBlock.TextContent("answer", "U0lHTg=="))));

        var part = GoogleWireBody.parts(contents, 1).get(0);
        assertThat(part.path("text").asText()).isEqualTo("answer");
        assertThat(part.path("thoughtSignature").asText()).isEqualTo("U0lHTg==");
    }

    @Test
    void sameModelReplaysThinkingSignature() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistantFrom("google", "gemini-2.5-flash",
                new ContentBlock.ThinkingContent("hmm", "U0lHTg=="))));

        var part = GoogleWireBody.parts(contents, 1).get(0);
        assertThat(part.path("thought").asBoolean()).isTrue();
        assertThat(part.path("text").asText()).isEqualTo("hmm");
        assertThat(part.path("thoughtSignature").asText()).isEqualTo("U0lHTg==");
    }

    @Test
    void sameModelReplaysToolCallSignature() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistantFrom("google", "gemini-2.5-flash",
                new ContentBlock.ToolUseContent("call_a", "read",
                    Map.of("path", "a.txt"), "U0lHTg=="))));

        var part = GoogleWireBody.parts(contents, 1).get(0);
        assertThat(part.path("functionCall").path("name").asText()).isEqualTo("read");
        assertThat(part.path("thoughtSignature").asText()).isEqualTo("U0lHTg==");
    }

    /** pi :237-244：空文本但带签名的 part 必须保留回送（空 part 也要回）。 */
    @Test
    void emptyTextWithSignatureIsKept() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistantFrom("google", "gemini-2.5-flash",
                new ContentBlock.TextContent("", "U0lHTg=="))));

        var parts = GoogleWireBody.parts(contents, 1);
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).path("text").asText()).isEqualTo("");
        assertThat(parts.get(0).path("thoughtSignature").asText()).isEqualTo("U0lHTg==");
    }

    /**
     * 跨身份：thinking 块降级为纯文本（无 thought:true、无签名），不是整块丢弃。
     * pi :260-269。
     */
    @Test
    void crossModelDowngradesThinkingToPlainText() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistantFrom("anthropic", "claude-sonnet-5",
                new ContentBlock.ThinkingContent("hmm", "U0lHTg=="))));

        var part = GoogleWireBody.parts(contents, 1).get(0);
        assertThat(part.path("text").asText()).isEqualTo("hmm");
        assertThat(part.has("thought")).isFalse();
        assertThat(part.has("thoughtSignature")).isFalse();
    }

    /** 负对照：跨模型文本块上不附签名（M2 变异探针的有牙对照）。 */
    @Test
    void crossModelStripsSignatureFromText() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistantFrom("anthropic", "claude-sonnet-5",
                new ContentBlock.TextContent("answer", "U0lHTg=="))));

        var part = GoogleWireBody.parts(contents, 1).get(0);
        assertThat(part.path("text").asText()).isEqualTo("answer");
        assertThat(part.has("thoughtSignature")).isFalse();
    }

    /** 负对照：同模型但非法 base64 的签名被丢弃、文本存活（M3 变异探针的有牙对照）。 */
    @Test
    void invalidBase64SignatureIsDropped() throws Exception {
        var contents = GoogleWireBody.contents(vision(GEMINI_25), List.of(
            user("hi"),
            assistantFrom("google", "gemini-2.5-flash",
                new ContentBlock.TextContent("answer", "%%%%not-base64"))));

        var part = GoogleWireBody.parts(contents, 1).get(0);
        assertThat(part.path("text").asText()).isEqualTo("answer");
        assertThat(part.has("thoughtSignature")).isFalse();
    }
}
