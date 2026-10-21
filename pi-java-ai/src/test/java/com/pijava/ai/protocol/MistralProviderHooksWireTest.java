package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
 * docs/17（B248，T1＋T2）：mistral 车道（自备 HTTP）的请求面三钩——
 * pi {@code mistral-conversations.ts:148/:330/:611} 对位；{@code onResponse}
 * 走 T2 的 {@code SseResponseObserver} 终态面（<b>含错误状态臂</b>，pi fetch
 * 车道在 {@code !ok} 判定前 fire）。
 */
class MistralProviderHooksWireTest {

    private static String chunk(String content, String finishReason) {
        return "data: {\"id\":\"a\",\"object\":\"chat.completion.chunk\",\"created\":0,"
            + "\"model\":\"mistral-large-latest\",\"choices\":[{\"index\":0,"
            + "\"delta\":{\"content\":\"" + content + "\"},"
            + "\"finish_reason\":" + finishReason + "}]}\n\n";
    }

    private static final byte[] SUCCESS_SSE = (
            chunk("hi", "null")
            + chunk("", "\"stop\"")
            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(ModelId.of("mistral", "mistral-large-latest"),
            "", List.of(user), List.of(), -1, -1);
    }

    private static ApiOptions options(String baseUrl, Map<String, Object> hooks) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0,
            new java.util.LinkedHashMap<>(hooks));
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new MistralConversationsApi(options);
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    @Test
    @SuppressWarnings("unchecked") // payload 是车道原生 Map（docs/17 §3.2）
    void payloadMapReplacementLandsOnTheWire() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));
            ProviderHooks.PayloadHook hook = (payload, model) -> {
                var body = new java.util.LinkedHashMap<>((Map<String, Object>) payload);
                body.put("max_tokens", 13);
                return body;
            };
            var raw = new ArrayList<Object>();
            ProviderHooks.StreamEventHook streamHook = (data, model) -> raw.add(data);

            var events = runStream(options(server.baseUrl(), Map.of(
                ProviderHooks.ON_PAYLOAD, hook,
                ProviderHooks.ON_PROVIDER_STREAM_EVENT, streamHook)));

            assertThat(server.body()).contains("\"max_tokens\":13");
            // pi mc.ts:611：扩展看到的是**解析后** chunk（java 对位＝解析出的 Map），
            // [DONE] 哨兵不到达（pi 的 SSE 解析器同形）。
            assertThat(raw).isNotEmpty().allSatisfy(
                data -> assertThat(data).isInstanceOf(Map.class));
            assertThat(events).anyMatch(
                e -> e instanceof StreamEvent.StreamDone d && d.reason() == StopReason.STOP);
        }
    }

    @Test
    void responseHookSeesTerminalResponseOnSuccess() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream",
                            "x-hook-probe", "present"),
                    SUCCESS_SSE));
            var seen = new ArrayList<Map<Integer, Map<String, String>>>();
            ProviderHooks.ResponseHook hook = (status, headers, model) ->
                seen.add(Map.of(status, headers));

            runStream(options(server.baseUrl(), Map.of(ProviderHooks.ON_RESPONSE, hook)));

            assertThat(seen).hasSize(1);
            assertThat(seen.get(0)).containsKey(200);
            assertThat(seen.get(0).get(200).entrySet())
                .anySatisfy(entry -> {
                    assertThat(entry.getKey().toLowerCase(java.util.Locale.ROOT))
                        .isEqualTo("x-hook-probe");
                    assertThat(entry.getValue()).isEqualTo("present");
                });
        }
    }

    @Test
    void responseHookFiresForErrorStatusToo() throws Exception {
        try (var server = new RecordingHttpServer()) {
            // 400 终态（零重试）——pi fetch 车道在 !ok 判定前 fire（pi-messages.ts:404
            // 同位），错误状态照样到达扩展。
            var seen = new ArrayList<Integer>();
            ProviderHooks.ResponseHook hook = (status, headers, model) -> seen.add(status);

            var events = runStream(options(server.baseUrl(),
                Map.of(ProviderHooks.ON_RESPONSE, hook)));

            assertThat(seen).containsExactly(400);
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }
}
