package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.genai.types.GenerateContentResponse;

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
 * docs/17（B248，T1）：google 车道的请求面两钩——pi
 * {@code google-generative-ai.ts:96/:107} 对位；{@code onResponse} <b>不发</b>
 * （pi 的 google 两车道全文件零命中 ⇒ java 同缺＝对齐，本夹具钉住防回潮）。
 */
class GoogleProviderHooksWireTest {

    private static String sseData(String candidateFields) {
        return "data: {\"candidates\":[{" + candidateFields + "}],"
                + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}\n\n";
    }

    private static final byte[] SUCCESS_SSE = (
            sseData("\"content\":{\"parts\":[{\"text\":\"hi\"}],\"role\":\"model\"}")
            + sseData("\"finishReason\":\"STOP\"")).getBytes(StandardCharsets.UTF_8);

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return StreamRequest.of(ModelId.of("google", "gemini-2.5-flash"), List.of(user));
    }

    private static ApiOptions options(String baseUrl, Map<String, Object> hooks) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0,
            new java.util.LinkedHashMap<>(hooks));
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new GoogleGenerativeAiApi(options);
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    @Test
    void googlePayloadCarrierReplacementLandsOnTheWire() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));
            var payloads = new ArrayList<Object>();
            ProviderHooks.PayloadHook hook = (payload, model) -> {
                payloads.add(payload);
                // 载体＝GooglePayload（窄差 D32）；替换 config（塞一个 stopSequences）。
                var g = (GooglePayload) payload;
                return new GooglePayload(g.model(), g.contents(),
                    g.config().toBuilder().stopSequences(List.of("ZZZ")).build());
            };
            var raw = new ArrayList<Object>();
            ProviderHooks.StreamEventHook streamHook = (data, model) -> raw.add(data);
            var statuses = new ArrayList<Integer>();
            ProviderHooks.ResponseHook responseHook = (status, headers, model) ->
                statuses.add(status);

            var events = runStream(options(server.baseUrl(), Map.of(
                ProviderHooks.ON_PAYLOAD, hook,
                ProviderHooks.ON_PROVIDER_STREAM_EVENT, streamHook,
                ProviderHooks.ON_RESPONSE, responseHook)));

            assertThat(payloads).hasSize(1);
            assertThat(payloads.get(0)).isInstanceOf(GooglePayload.class);
            assertThat(server.body()).contains("ZZZ");
            assertThat(raw).isNotEmpty().allSatisfy(
                data -> assertThat(data).isInstanceOf(GenerateContentResponse.class));
            // pi 同缺钉：google 车道永不 fire onResponse（pi 全文件零命中）。
            assertThat(statuses).isEmpty();
            assertThat(events).anyMatch(
                e -> e instanceof StreamEvent.StreamDone d && d.reason() == StopReason.STOP);
        }
    }

    @Test
    void wrongPayloadTypeFailsTheRequest() throws Exception {
        try (var server = new RecordingHttpServer()) {
            ProviderHooks.PayloadHook hook = (payload, model) -> "garbage";

            var events = runStream(options(server.baseUrl(),
                Map.of(ProviderHooks.ON_PAYLOAD, hook)));

            assertThat(server.requestCount()).isZero();
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError err
                && err.error().getMessage().contains("before_provider_request"));
        }
    }
}
