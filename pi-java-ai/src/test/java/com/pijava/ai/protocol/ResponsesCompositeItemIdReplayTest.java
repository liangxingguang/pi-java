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
import com.pijava.ai.message.StopReason;
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
            StopReason.TOOL_USE, null, "openai-responses", "openai", model, zeroUsage(),
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
                -1, -1);
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

    /**
     * B147（刻意偏差，登记留 §4）：java 三桶收集与 pi 单循环的**块序**有差，但出站
     * items 的分组序固定为 {@code reasoning ⇒ 文本 ⇒ 工具调用}。本夹具把块按
     * 「工具／思考／文本／工具」交错塞入，钉死分组序与同组内顺序，且 rs_／fc_ 的
     * id 配对不依赖交错位置（签名里各自带 id）。
     */
    @Test
    void interleavedBlocksEmitInGroupedOrder() throws Exception {
        var history = new ArrayList<Message>();
        history.add(user());
        history.add(new Message.AssistantMessage(
            List.of(
                new ContentBlock.ToolUseContent("call_1|fc_1001", "first", Map.of()),
                new ContentBlock.ThinkingContent("hmm",
                    "{\"id\":\"rs_9\",\"type\":\"reasoning\","
                        + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"hmm\"}]}"),
                new ContentBlock.TextContent("answer"),
                new ContentBlock.ToolUseContent("call_2|fc_1002", "second", Map.of())),
            StopReason.TOOL_USE, null, "openai-responses", "openai", "gpt-5.4",
            zeroUsage(), Instant.EPOCH, null, null));

        var body = capture("gpt-5.4", history);

        var types = new ArrayList<String>();
        body.path("input").forEach(item -> types.add(item.path("type").asText()));
        assertThat(types)
            .as("交错块必须按 reasoning ⇒ message ⇒ function_call × 2 分组出线")
            .containsSequence("reasoning", "message", "function_call", "function_call");

        var reasoning = itemOfType(body, "reasoning");
        assertThat(reasoning.path("id").asText()).isEqualTo("rs_9");
        var calls = new ArrayList<String>();
        body.path("input").forEach(item -> {
            if ("function_call".equals(item.path("type").asText())) {
                calls.add(item.path("call_id").asText());
            }
        });
        assertThat(calls).containsExactly("call_1", "call_2");
    }
}
