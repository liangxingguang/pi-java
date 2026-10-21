package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B80 请求半 ＋ B239：Mistral assistant 重放的线格形状 —— 对应 pi
 * {@code test/mistral-http-transport.test.ts:161-236}（"serializes assistant thinking,
 * tool calls, and tool results for replay"）。
 *
 * <p>旧实现的 assistant 分支只发文本串：thinking 块被滤掉、{@code tool_calls} 整块消失、
 * {@code prefix:false} 缺席 —— 多轮工具调用在第二轮就是孤儿 {@code role:"tool"} 消息。
 * 观测面＝反射调 {@code buildRequestBody} 的出参 JSON（先例 {@code MistralSurrogateSanitizeTest}），
 * 解成树逐值比对（pi 原测也是 {@code JSON.parse} 后比对象，键序不敏感）。</p>
 */
class MistralReplayWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PNG_B64 = "aGVsbG8=";
    private static final ModelId<?> TARGET = ModelId.of("mistral", "mistral-large-latest");
    private static final ModelInfo VISION = new ModelInfo(TARGET, "Mistral Large",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            128_000, 8_192, false, PricingInfo.UNKNOWN);

    // ── 出参构造（反射接缝，不触网） ─────────────────────────────────────

    private static JsonNode messages(List<Message> transcript) throws Exception {
        var api = new MistralConversationsApi(new ApiOptions(
            "http://localhost:1", "test-key", Duration.ofSeconds(5), 0, Map.of()));
        // docs/17：buildRequestBody → buildRequestBodyMap（onPayload 缝），观测面不变。
        var method = MistralConversationsApi.class.getDeclaredMethod(
            "buildRequestBodyMap", StreamRequest.class);
        method.setAccessible(true);
        var request = new StreamRequest(VISION, null, transcript, List.of(), -1, -1);
        return JSON.readTree(JSON.writeValueAsString(method.invoke(api, request))).get("messages");
    }

    /** 同模型身份三元组（api/provider/model 与 TARGET 一致 ⇒ 闸保留 thinking 原形）。 */
    private static Message.AssistantMessage assistant(List<ContentBlock> blocks) {
        return new Message.AssistantMessage(blocks, StopReason.TOOL_USE, null,
            "mistral-conversations", "mistral", TARGET.modelName(),
            null, null, null, null);
    }

    private static Message.ToolResultMessage toolResult(List<ContentBlock> blocks) {
        return new Message.ToolResultMessage("abc123456", "lookup", blocks,
            null, null, null, false, null);
    }

    // ── pi 黄金用例（http-transport.test.ts:161-236） ────────────────────

    @Test
    void serializesThinkingTextToolCallsAndToolResults() throws Exception {
        List<Message> transcript = List.of(
            assistant(List.of(
                new ContentBlock.ThinkingContent("reason"),
                new ContentBlock.TextContent("answer"),
                new ContentBlock.ToolUseContent("abc123456", "lookup",
                    Map.of("query", "pi")))),
            toolResult(List.of(
                new ContentBlock.TextContent("found"),
                new ContentBlock.ImageContent("image/png", PNG_B64))));

        assertThat(messages(transcript)).isEqualTo(JSON.readTree("""
            [
              {
                "role": "assistant",
                "prefix": false,
                "content": [
                  { "type": "thinking", "thinking": [{ "type": "text", "text": "reason" }] },
                  { "type": "text", "text": "answer" }
                ],
                "tool_calls": [
                  {
                    "id": "abc123456",
                    "type": "function",
                    "function": { "name": "lookup", "arguments": "{\\"query\\":\\"pi\\"}" },
                    "index": 0
                  }
                ]
              },
              {
                "role": "tool",
                "tool_call_id": "abc123456",
                "name": "lookup",
                "content": [
                  { "type": "text", "text": "found" },
                  { "type": "image_url", "image_url": "data:image/png;base64,aGVsbG8=" }
                ]
              }
            ]
            """));
    }

    // ── pi :846/:852 trim 门 ＋ :871 皆空不落线 ─────────────────────────

    /** text/thinking 都用 trim 判空；只剩工具块 ⇒ content 键缺席，tool_calls 仍在。 */
    @Test
    void blankPartsAreDroppedButToolCallsRemain() throws Exception {
        List<Message> transcript = List.of(assistant(List.of(
            new ContentBlock.TextContent("   "),
            new ContentBlock.ThinkingContent("\n\t "),
            new ContentBlock.ToolUseContent("abc123456", "lookup", Map.of()))));

        var first = messages(transcript).get(0);
        assertThat(first.get("role").asText()).isEqualTo("assistant");
        assertThat(first.get("prefix").asBoolean()).isFalse();
        assertThat(first.has("content")).isFalse();
        var toolCall = first.get("tool_calls").get(0);
        assertThat(toolCall.get("id").asText()).isEqualTo("abc123456");
        assertThat(toolCall.get("index").asInt()).isZero(); // pi :864 恒 0
        assertThat(toolCall.get("function").get("arguments").asText()).isEqualTo("{}");
    }

    /** parts/toolCalls 皆空 ⇒ pi :871 整条消息不落线（没有 system 时 messages 为 []）。 */
    @Test
    void assistantWithoutAnyPartIsOmittedEntirely() throws Exception {
        List<Message> transcript = List.of(assistant(List.of(
            new ContentBlock.TextContent("  "))));

        assertThat(messages(transcript)).isEmpty();
    }

    /** pi 怪癖照抄：每个 toolCall 的 index 恒为 0（:864），不去重不递增。 */
    @Test
    void everyToolCallCarriesIndexZero() throws Exception {
        List<Message> transcript = List.of(assistant(List.of(
            new ContentBlock.ToolUseContent("abc123456", "lookup", Map.of("q", "1")),
            new ContentBlock.ToolUseContent("def789012", "read", Map.of("path", "a")))));

        var toolCalls = messages(transcript).get(0).get("tool_calls");
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls.get(0).get("index").asInt()).isZero();
        assertThat(toolCalls.get(1).get("index").asInt()).isZero();
        assertThat(toolCalls.get(1).get("id").asText()).isEqualTo("def789012");
    }

    /**
     * 异模型身份（闸在 {@link com.pijava.ai.api.TransformMessages}）：无签名 thinking
     * 已被降级成 TextContent ⇒ 本车道看到的就是 {@code {type:"text"}} 项，不发 thinking。
     */
    @Test
    void crossModelThinkingIsReplayedAsText() throws Exception {
        var foreign = new Message.AssistantMessage(
            List.of(new ContentBlock.ThinkingContent("reason"),
                    new ContentBlock.TextContent("answer")),
            StopReason.STOP, null,
            "anthropic-messages", "anthropic", "claude-x",
            null, null, null, null);

        var content = messages(List.<Message>of(foreign)).get(0).get("content");
        assertThat(content).isEqualTo(JSON.readTree("""
            [
              { "type": "text", "text": "reason" },
              { "type": "text", "text": "answer" }
            ]
            """));
    }
}
