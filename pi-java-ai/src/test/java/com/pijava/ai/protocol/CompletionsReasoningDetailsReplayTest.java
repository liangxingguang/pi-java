package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
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
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

/**
 * <b>Batch F 步 7</b>（{@code 原 docs/67}）：Completions {@code reasoning_details}
 * 的**重放** —— pi 从 thinking 块签名解析结构化 details、从 toolCall.thoughtSignature
 * 解析 legacy 加密项，挂到助手消息的 reasoning_details；有 details 时不发裸
 * reasoning 字段（互斥）。{@code openai-completions.ts:1301-1307/:1329-1339/:1373-1375}。
 */
class CompletionsReasoningDetailsReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SIGNED_DETAILS_JSON =
        "[{\"type\":\"reasoning.encrypted\",\"id\":\"rs_1\",\"data\":\"ENC-1234\"}]";

    private static final String LEGACY_DETAIL_JSON =
        "{\"type\":\"reasoning.encrypted\",\"id\":\"rs_1\",\"data\":\"LEGACY-ENC\"}";

    @Test
    void replaysSignedReasoningDetails() throws Exception {
        var history = history(assistant(List.of(
            new ContentBlock.TextContent("answer"),
            new ContentBlock.ThinkingContent("hmm", SIGNED_DETAILS_JSON))));

        var assistant = assistantEntry(body(history));

        var details = assistant.path("reasoning_details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).path("type").asText()).isEqualTo("reasoning.encrypted");
        assertThat(details.get(0).path("data").asText()).isEqualTo("ENC-1234");
        // Details are the structured alternative: no raw reasoning key.
        assertThat(assistant.has("reasoning_content")).isFalse();
    }

    /**
     * Legacy details from toolCall.thoughtSignature win once no signed details
     * exist, and suppress the raw reasoning field (mutex).
     */
    @Test
    void replaysLegacyDetailsAndSuppressesRawField() throws Exception {
        var history = history(assistant(List.of(
            new ContentBlock.TextContent("answer"),
            new ContentBlock.ThinkingContent("hmm", "reasoning_content"),
            new ContentBlock.ToolUseContent("call_1", "read", Map.of(), LEGACY_DETAIL_JSON))));

        var assistant = assistantEntry(body(history));

        var details = assistant.path("reasoning_details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).path("data").asText()).isEqualTo("LEGACY-ENC");
        assertThat(assistant.has("reasoning_content"))
            .as("有 details 时裸 reasoning_content 必须互斥不发")
            .isFalse();
    }

    /** Legacy details survive with no thinking block at all. */
    @Test
    void replaysLegacyDetailsWithoutThinking() throws Exception {
        var history = history(assistant(List.of(
            new ContentBlock.TextContent("answer"),
            new ContentBlock.ToolUseContent("call_1", "read", Map.of(), LEGACY_DETAIL_JSON))));

        var assistant = assistantEntry(body(history));

        assertThat(assistant.path("reasoning_details")).hasSize(1);
        assertThat(assistant.path("reasoning_details").get(0).path("id").asText())
            .isEqualTo("rs_1");
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    /** Assistant history carrying the same identity as the request target. */
    private static Message.AssistantMessage assistant(List<ContentBlock> blocks) {
        return new Message.AssistantMessage(blocks,
            null, null, "openai-completions", "openrouter", "auto",
            null, null, null, null);
    }

    private static List<Message> history(Message.AssistantMessage assistant) {
        var messages = new ArrayList<Message>();
        messages.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
        messages.add(assistant);
        messages.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("next"))));
        return List.copyOf(messages);
    }

    /** Send one request against a 400 stub and read the real outgoing body. */
    private static JsonNode body(List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var request = new StreamRequest(model(), null, messages,
                List.of(), -1, -1, Map.of());
            var api = new OpenAICompletionsApi(new ApiOptions(
                server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // stub returns 400; the request body is recorded
            }
            return MAPPER.readTree(server.body());
        }
    }

    private static ModelInfo model() {
        var id = ModelId.of("openrouter", "auto");
        return new ModelInfo(id, "auto",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
            200_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of());
    }

    private static JsonNode assistantEntry(JsonNode body) {
        var assistants = new ArrayList<JsonNode>();
        body.path("messages").forEach(message -> {
            if ("assistant".equals(message.path("role").asText())) {
                assistants.add(message);
            }
        });
        assertThat(assistants).hasSize(1);
        return assistants.get(0);
    }
}
