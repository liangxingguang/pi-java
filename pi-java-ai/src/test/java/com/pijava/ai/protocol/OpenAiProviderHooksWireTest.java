package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/17（B248，T1）：openai-completions／openai-responses 两车道的请求面三钩
 * （pi {@code openai-completions.ts:366/:383/:559} 与 {@code openai-responses.ts:174}／
 * {@code openai-responses-shared.ts:600} 对位）。
 */
class OpenAiProviderHooksWireTest {

    // ── completions 车道 ────────────────────────────────────────────────

    private static final byte[] COMPLETIONS_SSE = String.join("\n",
            "data: {\"id\":\"a\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"a\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]", "", "").getBytes(StandardCharsets.UTF_8);

    // ── responses 车道 ──────────────────────────────────────────────────

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

    private static StreamRequest request(String provider, String modelName) {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(ModelInfo.minimal(ModelId.of(provider, modelName)),
            "", List.of(user), List.of(), -1, -1);
    }

    private static ApiOptions options(String baseUrl, Map<String, Object> hooks) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0,
            new java.util.LinkedHashMap<>(hooks));
    }

    private static List<StreamEvent> runStream(AbstractChatApi api, StreamRequest request) {
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    @Test
    void completionsPayloadReplacementAndHooksLand() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream",
                            "x-hook-probe", "present"),
                    COMPLETIONS_SSE));
            ProviderHooks.PayloadHook payloadHook = (payload, model) ->
                ((ChatCompletionCreateParams) payload).toBuilder().maxTokens(9).build();
            var statuses = new ArrayList<Integer>();
            ProviderHooks.ResponseHook responseHook = (status, headers, model) ->
                statuses.add(status);
            var raw = new ArrayList<Object>();
            ProviderHooks.StreamEventHook streamHook = (data, model) -> raw.add(data);

            var events = runStream(
                new OpenAICompletionsApi(options(server.baseUrl(), Map.of(
                    ProviderHooks.ON_PAYLOAD, payloadHook,
                    ProviderHooks.ON_RESPONSE, responseHook,
                    ProviderHooks.ON_PROVIDER_STREAM_EVENT, streamHook))),
                request("openai", "gpt-4o"));

            assertThat(server.body()).contains("\"max_tokens\":9");
            assertThat(statuses).containsExactly(200);
            assertThat(raw).isNotEmpty().allSatisfy(
                data -> assertThat(data).isInstanceOf(ChatCompletionChunk.class));
            assertThat(events).anyMatch(
                e -> e instanceof StreamEvent.StreamDone d && d.reason() == StopReason.STOP);
        }
    }

    @Test
    void responsesPayloadReplacementAndHooksLand() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"),
                    RESPONSES_SSE.getBytes(StandardCharsets.UTF_8)));
            ProviderHooks.PayloadHook payloadHook = (payload, model) ->
                ((ResponseCreateParams) payload).toBuilder()
                    .maxOutputTokens(11).build();
            var statuses = new ArrayList<Integer>();
            ProviderHooks.ResponseHook responseHook = (status, headers, model) ->
                statuses.add(status);
            var raw = new ArrayList<Object>();
            ProviderHooks.StreamEventHook streamHook = (data, model) -> raw.add(data);

            var events = runStream(
                new OpenAIResponsesApi(options(server.baseUrl(), Map.of(
                    ProviderHooks.ON_PAYLOAD, payloadHook,
                    ProviderHooks.ON_RESPONSE, responseHook,
                    ProviderHooks.ON_PROVIDER_STREAM_EVENT, streamHook))),
                request("openai", "gpt-4o"));

            assertThat(server.body()).contains("\"max_output_tokens\":11");
            assertThat(statuses).containsExactly(200);
            assertThat(raw).isNotEmpty().allSatisfy(
                data -> assertThat(data).isInstanceOf(ResponseStreamEvent.class));
            assertThat(events).anyMatch(
                e -> e instanceof StreamEvent.StreamDone d && d.reason() == StopReason.STOP);
        }
    }

    @Test
    void responsesWrongPayloadTypeFailsTheRequest() throws Exception {
        try (var server = new RecordingHttpServer()) {
            ProviderHooks.PayloadHook payloadHook = (payload, model) -> 42;

            var events = runStream(
                new OpenAIResponsesApi(options(server.baseUrl(),
                    Map.of(ProviderHooks.ON_PAYLOAD, payloadHook))),
                request("openai", "gpt-4o"));

            assertThat(server.requestCount()).isZero();
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError err
                && err.error().getMessage().contains("before_provider_request"));
        }
    }
}
