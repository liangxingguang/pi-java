package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * D3（{@code docs/62}）：Responses 接收流把工具调用 id 存为复合
 * {@code call_id|item.id}（pi {@code openai-responses-shared.ts:485-489}）。
 */
class ResponsesCompositeItemIdWireTest {

    private static byte[] functionCallSse() {
        return String.join("\n",
            "event: response.output_item.added",
            "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"
                + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"id\":\"fc_1000\","
                + "\"name\":\"get_weather\",\"arguments\":\"\"}}",
            "",
            "event: response.function_call_arguments.delta",
            "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{}\"}",
            "",
            "event: response.function_call_arguments.done",
            "data: {\"type\":\"response.function_call_arguments.done\",\"output_index\":0,\"arguments\":\"{}\"}",
            "",
            "event: response.output_item.done",
            "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
                + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"id\":\"fc_1000\","
                + "\"name\":\"get_weather\",\"arguments\":\"{}\"}}",
            "",
            "event: response.completed",
            "data: {\"type\":\"response.completed\",\"response\":{"
                + "\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":0,"
                + "\"error\":null,\"incomplete_details\":null,\"instructions\":null,"
                + "\"metadata\":{},\"parallel_tool_calls\":true,\"temperature\":1.0,"
                + "\"tool_choice\":\"auto\",\"tools\":[],\"top_p\":1.0,"
                + "\"status\":\"completed\",\"model\":\"gpt-5.4\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":10,\"input_tokens_details\":"
                + "{\"cached_tokens\":0,\"cache_write_tokens\":0},"
                + "\"output_tokens\":5,\"output_tokens_details\":"
                + "{\"reasoning_tokens\":0},\"total_tokens\":15}}}",
            "", "").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void toolCallIdCombinesCallIdAndItemId() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(200,
                Map.of("Content-Type", "text/event-stream"), functionCallSse()));

            var options = new ApiOptions(server.baseUrl(), "test-key",
                Duration.ofSeconds(5), 0, Map.of());
            var api = new OpenAIResponsesApi(options, "OPENAI_API_KEY");
            var model = ModelInfo.minimal(ModelId.of("openai", "gpt-5.4"));
            Message user = new Message.UserMessage(
                List.of(new ContentBlock.TextContent("weather?")));
            var request = new StreamRequest(model, null, List.of(user), List.of(),
                -1, -1, Map.of());

            Message result = api.send(request, options);

            assertThat(result).isInstanceOf(Message.AssistantMessage.class);
            var msg = (Message.AssistantMessage) result;
            assertThat(msg.stopReason()).isEqualTo("toolUse");
            assertThat(msg.content())
                .singleElement()
                .isInstanceOf(ContentBlock.ToolUseContent.class)
                .satisfies(b -> {
                    var call = (ContentBlock.ToolUseContent) b;
                    // pi：id ＝ `${call_id}|${item.id}`，单一字段贯穿全链路。
                    assertThat(call.id()).isEqualTo("call_1|fc_1000");
                    assertThat(call.name()).isEqualTo("get_weather");
                });
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }
}
