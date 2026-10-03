package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.ShortHash;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>docs/71 G2</b>：Responses 请求侧的回执 id 链 —— 逐行照 pi
 * {@code openai-responses-shared.ts:267-287}。
 *
 * <p>pi 的规则：每个**文本块**推一条 {@code message} item；id 依次取
 * 签名里的 id → {@code msg_pi_${msgIndex}}（首块）/ {@code msg_pi_${msgIndex}_${i}}
 * （后续块）→ 超过 64 字符时 {@code msg_${shortHash(id)}}；{@code phase} 随签名上线。</p>
 */
class ResponsesTextSignatureReplayTest {

    private static final ModelId<?> TARGET = ModelId.of("openai", "gpt-5-mini");

    private static ResponseCreateParams buildParams(List<Message> messages) throws Exception {
        var request = new StreamRequest(TARGET, null, messages, List.of(), 100, 0.5, Map.of());
        return ResponsesMessageConverter.buildParams(request,
            ResponsesOptions.from(ApiOptions.defaults()), "gpt-5-mini", "openai-responses",
            CompatResolver.forResponses(request.model(), false));
    }

    private static List<ResponseOutputMessage> outputMessages(ResponseCreateParams params) {
        var out = new ArrayList<ResponseOutputMessage>();
        for (var item : params.input().orElseThrow().asResponse()) {
            if (item.isResponseOutputMessage()) {
                out.add(item.asResponseOutputMessage());
            }
        }
        return out;
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            "openai-responses", TARGET.provider(), TARGET.modelName(), null, null, null, null);
    }

    private static List<ResponseOutputMessage> convert(ContentBlock... blocks) throws Exception {
        return outputMessages(buildParams(List.of(user("hi"), assistant(blocks))));
    }

    // ── id 链 ──────────────────────────────────────────────────────────

    @Test
    void withoutSignatureFallsBackToTheIndexedId() throws Exception {
        var messages = convert(new ContentBlock.TextContent("hello"));

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).id()).isEqualTo("msg_pi_1"); // 助手消息在整表的下标 1
    }

    /** 后续文本块带 {@code _${textBlockIndex}} 后缀（pi 的 fallback 分支）。 */
    @Test
    void secondTextBlockGetsTheSuffixFallback() throws Exception {
        var messages = convert(new ContentBlock.TextContent("a"),
            new ContentBlock.TextContent("b"));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).id()).isEqualTo("msg_pi_1");
        assertThat(messages.get(1).id()).isEqualTo("msg_pi_1_1");
    }

    @Test
    void signatureIdWinsOverTheFallback() throws Exception {
        var signature = TextSignatureV1.encode("msg_original", null);

        var messages = convert(new ContentBlock.TextContent("hello", signature));

        assertThat(messages.get(0).id()).isEqualTo("msg_original");
    }

    /** OpenAI 要求 id ≤ 64 字符 ⇒ 超长走 {@code msg_${shortHash(id)}}（pi :278-284）。 */
    @Test
    void overlongSignatureIdIsShortHashed() throws Exception {
        var longId = "msg_" + "x".repeat(80);
        var signature = TextSignatureV1.encode(longId, null);

        var messages = convert(new ContentBlock.TextContent("hello", signature));

        assertThat(messages.get(0).id()).isEqualTo("msg_" + ShortHash.of(longId));
    }

    /** 恰好 64 字符 ⇒ **不**走哈希（pi 是 `> 64`）。 */
    @Test
    void exactlySixtyFourCharsIsKept() throws Exception {
        var id = "m".repeat(64);

        var messages = convert(new ContentBlock.TextContent("hello",
            TextSignatureV1.encode(id, null)));

        assertThat(messages.get(0).id()).isEqualTo(id);
    }

    /** legacy 裸 id 同样生效（"{" 开头的才是新形）。 */
    @Test
    void legacyBareSignatureIdIsUsed() throws Exception {
        var messages = convert(new ContentBlock.TextContent("hello", "msg_legacy"));

        assertThat(messages.get(0).id()).isEqualTo("msg_legacy");
    }

    // ── phase ──────────────────────────────────────────────────────────

    @Test
    void signaturePhaseIsReplayed() throws Exception {
        var signature = TextSignatureV1.encode("msg_x", "final_answer");

        var messages = convert(new ContentBlock.TextContent("hello", signature));

        assertThat(messages.get(0).phase()).contains(ResponseOutputMessage.Phase.FINAL_ANSWER);
    }

    @Test
    void absentPhaseIsNotSet() throws Exception {
        var messages = convert(new ContentBlock.TextContent("hello", "msg_x"));

        assertThat(messages.get(0).phase()).isEmpty();
    }

    // ── 形状 ───────────────────────────────────────────────────────────

    /** 三个文本块 ⇒ 三条 message item（不是拼成一条）。 */
    @Test
    void oneOutputMessagePerTextBlock() throws Exception {
        var messages = convert(new ContentBlock.TextContent("a"),
            new ContentBlock.TextContent("b"),
            new ContentBlock.TextContent("c"));

        assertThat(messages).hasSize(3);
        assertThat(messages).extracting(ResponseOutputMessage::id)
            .containsExactly("msg_pi_1", "msg_pi_1_1", "msg_pi_1_2");
        assertThat(messages).allSatisfy(m -> assertThat(m.content()).hasSize(1));
    }

    /** 文本块下标**只数文本块**：中间夹一个 thinking 块不吃掉下标。 */
    @Test
    void textBlockIndexCountsTextBlocksOnly() throws Exception {
        var messages = convert(new ContentBlock.TextContent("a"),
            new ContentBlock.ThinkingContent("t", ""),
            new ContentBlock.TextContent("b"));

        assertThat(messages).extracting(ResponseOutputMessage::id)
            .containsExactly("msg_pi_1", "msg_pi_1_1");
    }

    /** 没有文本块 ⇒ 一条 message 都不推（工具调用走自己的 item）。 */
    @Test
    void noTextBlocksMeansNoOutputMessage() throws Exception {
        var messages = convert(new ContentBlock.ToolUseContent("call_1", "ls", Map.of()));

        assertThat(messages).isEmpty();
        assertThat(buildParams(List.of(user("hi"),
            assistant(new ContentBlock.ToolUseContent("call_1", "ls", Map.of()))))
            .input().orElseThrow().asResponse().stream()
            .anyMatch(ResponseInputItem::isFunctionCall)).isTrue();
    }
}
