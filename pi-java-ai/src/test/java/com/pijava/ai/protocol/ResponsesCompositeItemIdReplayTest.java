package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

/**
 * D3（{@code 原 docs/62}）：Responses 出站重放拆分复合 id —— function_call_output
 * 只用 call_id（pi shared :331-346）、function_call 恢复真实 item.id
 * （:288-293），跨模型/非 fc_ 时丢 id（:289-303）。
 */
class ResponsesCompositeItemIdReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Message.AssistantMessage assistant(String model, String compositeId) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent(compositeId, "get_weather", Map.of())),
            "toolUse", null, "openai-responses", "openai", model, zeroUsage(),
            Instant.EPOCH, null, null);
    }

    private static Message.ToolResultMessage toolResult(String compositeId) {
        return new Message.ToolResultMessage(compositeId, "get_weather",
            List.of(new ContentBlock.TextContent("sunny")), Map.of(), zeroUsage(),
            List.of(), false, null);
    }

    private static Usage zeroUsage() {
        return new Usage(0, 0, 0, 0, null, null, 0, Usage.Cost.zero());
    }

    private static Message.UserMessage user() {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent("weather?")));
    }

    /** 驱动车道，返回真出站请求体（桩回 400 收场，请求体已录）。 */
    private static JsonNode capture(String targetModel, List<Message> history)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            var modelInfo = ModelInfo.minimal(ModelId.of("openai", targetModel));
            var request = new StreamRequest(modelInfo, null, history, List.of(),
                -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录，流怎么结束无关。
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static JsonNode itemOfType(JsonNode body, String type) {
        for (var item : body.path("input")) {
            if (type.equals(item.path("type").asText())) {
                return item;
            }
        }
        throw new AssertionError("missing item of type " + type);
    }

    @Test
    void functionCallOutputUsesOnlyTheCallIdPart() throws Exception {
        var body = capture("gpt-5.4",
            List.of(user(), assistant("gpt-5.4", "call_1|fc_1000"),
                toolResult("call_1|fc_1000")));

        var output = itemOfType(body, "function_call_output");
        assertThat(output.path("call_id").asText()).isEqualTo("call_1");
        assertThat(output.path("call_id").asText()).doesNotContain("|");
    }

    @Test
    void replayedFunctionCallRestoresTheRealItemId() throws Exception {
        var body = capture("gpt-5.4",
            List.of(user(), assistant("gpt-5.4", "call_1|fc_1000")));

        var call = itemOfType(body, "function_call");
        assertThat(call.path("call_id").asText()).isEqualTo("call_1");
        // 真实历史 item.id，不是合成的 fc_<callId>。
        assertThat(call.path("id").asText()).isEqualTo("fc_1000");
    }

    @Test
    void differentModelHistoryDropsTheItemId() throws Exception {
        // 同 provider/api、不同模型：fc_ id 必须省略以避开 fc_↔rs_ 配对校验。
        var body = capture("gpt-5.5",
            List.of(user(), assistant("gpt-5.4", "call_1|fc_1000")));

        var call = itemOfType(body, "function_call");
        assertThat(call.path("call_id").asText()).isEqualTo("call_1");
        assertThat(call.has("id")).isFalse();
    }

    @Test
    void nonFcItemIdIsDropped() throws Exception {
        // custom tool 的 ctc_ id 不能作为 function_call 的 item id（必须 fc_）。
        var body = capture("gpt-5.4",
            List.of(user(), assistant("gpt-5.4", "call_2|ctc_xyz")));

        var call = itemOfType(body, "function_call");
        assertThat(call.path("call_id").asText()).isEqualTo("call_2");
        assertThat(call.has("id")).isFalse();
    }
}
