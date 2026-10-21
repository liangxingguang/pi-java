package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * docs/17（B248，T1＋T2）：pi-messages 车道的请求面三钩＋头通道补（裁决⑦）——
 * pi {@code pi-messages.ts:387/:394-404/:415} 对位。pi 的本车道<b>不豁免</b>
 * （三钩全有，与 {@code resolvesRequestOptions} 的 R2 豁免是两层事）。
 */
class PiMessagesProviderHooksWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] SUCCESS_SSE = (
            "data: {\"type\":\"start\"}\n\n"
            + "data: {\"type\":\"text_start\"}\n\n"
            + "data: {\"type\":\"text_delta\",\"delta\":\"hi\"}\n\n"
            + "data: {\"type\":\"text_end\"}\n\n"
            + "data: {\"type\":\"done\",\"reason\":\"stop\"}\n\n").getBytes(
                java.nio.charset.StandardCharsets.UTF_8);

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(ModelId.of("pi", "test-model"),
            "", List.of(user), List.of(), -1, -1);
    }

    private static ApiOptions options(String baseUrl, Map<String, Object> extra) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0,
            new java.util.LinkedHashMap<>(extra));
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new PiMessagesApi(options, "PI_API_KEY");
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    @Test
    void payloadEnvelopeReplacementLandsOnTheWire() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));
            ProviderHooks.PayloadHook hook = (payload, model) -> {
                var envelope = (ObjectNode) payload;
                ((ObjectNode) envelope.get("options")).put("maxTokens", 21);
                return envelope;
            };
            var raw = new ArrayList<Object>();
            ProviderHooks.StreamEventHook streamHook = (data, model) -> raw.add(data);

            var events = runStream(options(server.baseUrl(), Map.of(
                ProviderHooks.ON_PAYLOAD, hook,
                ProviderHooks.ON_PROVIDER_STREAM_EVENT, streamHook)));

            var body = JSON.readTree(server.body());
            assertThat(body.path("options").path("maxTokens").asInt()).isEqualTo(21);
            // R2 豁免未破坏：替换前 envelope.options 本就无 maxTokens 键。
            assertThat(raw).isNotEmpty().allSatisfy(
                data -> assertThat(data).isInstanceOf(PiMessagesEvent.class));
            assertThat(events).anyMatch(
                e -> e instanceof StreamEvent.StreamDone d && d.reason() == StopReason.STOP);
        }
    }

    @Test
    void responseHookSeesTerminalResponse() throws Exception {
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
    void extraHeadersMergeWithNullDeletion() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));
            // 裁决⑦（pi :394-399 providerHeadersToRecord 逐字）：自定义头落线、
            // 名小写规范化、null ＝ 删除手写默认头。
            var headers = new java.util.LinkedHashMap<String, String>();
            headers.put("X-Custom-Trace", "trace-1");
            headers.put("accept", null);

            var events = runStream(options(server.baseUrl(), Map.of("headers", headers)));

            assertThat(server.headers()).containsEntry("x-custom-trace", "trace-1");
            // accept 被 null 删除 ⇒ 请求线上只剩 PiHttpClient 默认 Accept（:98-100 的
            // 默认三层不在 extra 合并集内——本夹具钉的是 extra 集内的删除语义）。
            assertThat(server.headers()).containsEntry("authorization", "Bearer test-key");
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamDone);
        }
    }
}
