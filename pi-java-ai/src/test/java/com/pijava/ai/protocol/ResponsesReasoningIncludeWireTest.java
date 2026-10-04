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
 * <b>Batch F 步 5</b>（{@code 原 docs/67}）：Responses 请求的 {@code include} 门与
 * reasoning 历史项的重放。
 *
 * <p>pi 在 reasoningEffort/reasoningSummary 分支发
 * {@code include: ["reasoning.encrypted_content"]}（{@code openai-responses.ts:352}），
 * 并在历史回放时把 thinkingSignature 里的整个 reasoning item 推进 input
 * （{@code openai-responses-shared.ts:261-266}）。</p>
 */
class ResponsesReasoningIncludeWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REASONING_ITEM_JSON =
        "{\"id\":\"rs_1\",\"type\":\"reasoning\","
        + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"hmm\"}],"
        + "\"encrypted_content\":\"ENC-1234\"}";

    @Test
    void sendsIncludeWhenReasoningEffortIsSet() throws Exception {
        var model = model();
        Map<String, Object> extras = Map.of("reasoningEffort", "high");

        var body = body(model,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            extras);

        assertThat(body.path("include")).hasSize(1);
        assertThat(body.path("include").get(0).asText())
            .isEqualTo("reasoning.encrypted_content");
    }

    /** 无 reasoning 选项 ⇒ include 键缺席（M4 探针的行为面）。 */
    @Test
    void omitsIncludeWithoutReasoningOptions() throws Exception {
        var body = body(model(),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            Map.of());

        assertThat(body.has("include")).isFalse();
    }

    @Test
    void replaysReasoningItemFromThinkingSignature() throws Exception {
        var model = model();
        var history = new ArrayList<Message>();
        history.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));
        history.add(new Message.AssistantMessage(
            List.of(new ContentBlock.ThinkingContent("hmm", REASONING_ITEM_JSON)),
            null, null, "openai-responses", "openai", "gpt-5", null, null, null, null));
        history.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("next"))));

        var body = body(model, history, Map.of());

        var reasoningItems = new ArrayList<JsonNode>();
        body.path("input").forEach(item -> {
            if ("reasoning".equals(item.path("type").asText())) {
                reasoningItems.add(item);
            }
        });
        assertThat(reasoningItems).hasSize(1);
        assertThat(reasoningItems.get(0).path("id").asText()).isEqualTo("rs_1");
        assertThat(reasoningItems.get(0).path("encrypted_content").asText())
            .isEqualTo("ENC-1234");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static ModelInfo model() {
        var id = ModelId.of("openai", "gpt-5");
        return new ModelInfo(id, "gpt-5", Set.of(ModelCapability.TEXT),
            200_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of());
    }

    /** 送出一次请求，回读真出站字节（桩恒回 400，只关心请求体）。 */
    private static JsonNode body(ModelInfo model, List<Message> messages,
                                  Map<String, Object> extras) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var request = new StreamRequest(model, null, messages,
                List.of(), -1, -1, Map.of());
            var options = new ApiOptions(server.baseUrl(), "test-key",
                Duration.ofSeconds(5), 0, extras);
            var api = new OpenAIResponsesApi(options, "OPENAI_API_KEY");
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }
}
