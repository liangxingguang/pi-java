package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.openai.models.responses.ResponseCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/17（B248，T1＋T2）：azure 车道的 {@code onPayload} 与头通道补（裁决⑦——
 * pi {@code azure-openai-responses.ts:156-169} 的 defaultHeaders 对位；本车道
 * 此前从不读 {@code extra["headers"]}）。
 */
class AzureProviderHooksWireTest {

    private static String responseEvent(String json) {
        return "data: " + json + "\n\n";
    }

    private static final String RESPONSES_SSE =
            responseEvent("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"in_progress\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}")
            + responseEvent("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"in_progress\",\"content\":[]}}")
            + responseEvent("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\"hi\"}")
            + responseEvent("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"hi\",\"annotations\":[]}]}}")
            + responseEvent("{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"completed\",\"model\":\"gpt-4o\","
                + "\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"hi\",\"annotations\":[]}]}],"
                + "\"parallel_tool_calls\":true,\"tools\":[],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2,"
                + "\"input_tokens_details\":{\"cached_tokens\":0},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":0}}}}")
            + "data: [DONE]\n\n";

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(ModelId.of("azure", "gpt-4o"),
            "", List.of(user), List.of(), -1, -1);
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new AzureOpenAIResponsesApi(options, "AZURE_OPENAI_API_KEY");
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    @Test
    void extraHeadersAndPayloadReplacementLand() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"),
                    RESPONSES_SSE.getBytes(StandardCharsets.UTF_8)));
            ProviderHooks.PayloadHook payloadHook = (payload, model) ->
                ((ResponseCreateParams) payload).toBuilder().maxOutputTokens(17).build();
            var statuses = new ArrayList<Integer>();
            ProviderHooks.ResponseHook responseHook = (status, headers, model) ->
                statuses.add(status);

            var extra = new java.util.LinkedHashMap<String, Object>();
            extra.put("headers", Map.of("x-custom-trace", "trace-1"));
            extra.put(ProviderHooks.ON_PAYLOAD, payloadHook);
            extra.put(ProviderHooks.ON_RESPONSE, responseHook);
            var options = new ApiOptions(server.baseUrl(), "test-key",
                Duration.ofSeconds(5), 0, extra);

            var events = runStream(options);

            // 裁决⑦：extra["headers"] 自此对 azure 车道生效（pi defaultHeaders 对位）。
            assertThat(server.headers()).containsEntry("x-custom-trace", "trace-1");
            assertThat(server.body()).contains("\"max_output_tokens\":17");
            assertThat(statuses).containsExactly(200);
            assertThat(events).anyMatch(
                e -> e instanceof StreamEvent.StreamDone d && d.reason() == StopReason.STOP);
        }
    }
}
