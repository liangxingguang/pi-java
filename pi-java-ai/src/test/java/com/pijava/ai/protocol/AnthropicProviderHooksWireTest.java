package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;

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
 * docs/17（B248，T1）：anthropic 车道的请求面三钩在真 HTTP 线上的形状——
 * pi {@code anthropic-messages.ts:640/:657/:664} 三 firing 点逐位对账
 * （{@code onPayload} 替换落线／类型守卫／{@code onResponse} 成功臂与错误臂／
 * {@code onProviderStreamEvent} 原始事件先于归一化）。
 */
class AnthropicProviderHooksWireTest {

    private static byte[] sseEvent(String event, String data) {
        return ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] SUCCESS_SSE = concat(
            sseEvent("message_start",
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-5\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}"),
            sseEvent("content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
            sseEvent("content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}"),
            sseEvent("content_block_stop",
                    "{\"type\":\"content_block_stop\",\"index\":0}"),
            sseEvent("message_delta",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}"),
            sseEvent("message_stop", "{\"type\":\"message_stop\"}"));

    private static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static ModelInfo model() {
        return ModelInfo.minimal(ModelId.of("anthropic", "claude-sonnet-5"));
    }

    private static StreamRequest request() {
        Message user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(model(), "", List.of(user), List.of(), -1, -1);
    }

    private static ApiOptions options(String baseUrl, Map<String, Object> hooks) {
        var extra = new java.util.LinkedHashMap<String, Object>(hooks);
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, extra);
    }

    private static List<StreamEvent> runStream(ApiOptions options) {
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        var events = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    @Test
    void payloadReplacementLandsOnTheWire() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));
            ProviderHooks.PayloadHook hook = (payload, model) ->
                ((MessageCreateParams) payload).toBuilder().maxTokens(7).build();

            var events = runStream(options(server.baseUrl(),
                Map.of(ProviderHooks.ON_PAYLOAD, hook)));

            assertThat(server.body()).contains("\"max_tokens\":7");
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

            // 裁决⑤/D31：类型不符 ⇒ 请求失败（IllegalStateException 落成 StreamError），
            // 请求根本不出站。
            assertThat(server.requestCount()).isZero();
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError err
                && err.error() instanceof IllegalStateException
                && err.error().getMessage().contains("before_provider_request"));
        }
    }

    @Test
    void responseHookSeesStatusAndHeadersOnSuccess() throws Exception {
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
    void responseHookIsSilentWhenAcquisitionFails() throws Exception {
        try (var server = new RecordingHttpServer()) {
            // 默认桩恒 400、零重试 ⇒ 获取在 ProviderRetry 内抛（pi SDK 车道同形：
            // retryProviderRequest 内抛 ⇒ 到不了 onResponse）。
            var seen = new ArrayList<Integer>();
            ProviderHooks.ResponseHook hook = (status, headers, model) -> seen.add(status);

            var events = runStream(options(server.baseUrl(),
                Map.of(ProviderHooks.ON_RESPONSE, hook)));

            assertThat(seen).isEmpty();
            assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        }
    }

    @Test
    void streamEventHookSeesRawEventsBeforeNormalization() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));
            var raw = new ArrayList<Object>();
            ProviderHooks.StreamEventHook hook = (data, model) -> raw.add(data);
            var laneEvents = new ArrayList<StreamEvent>();
            var api = new AnthropicMessagesApi(
                options(server.baseUrl(), Map.of(ProviderHooks.ON_PROVIDER_STREAM_EVENT, hook)),
                "ANTHROPIC_API_KEY");
            // 逐条消费，记录「首条原始事件」与「首条归一化文本 delta」的先后。
            boolean rawBeforeFirstTextDelta = false;
            try (var iter = api.streamBlocking(request(), ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    var e = iter.next();
                    if (e instanceof StreamEvent.TextDelta && !raw.isEmpty()
                            && laneEvents.stream().noneMatch(x -> x instanceof StreamEvent.TextDelta)) {
                        rawBeforeFirstTextDelta = true;
                    }
                    laneEvents.add(e);
                }
            }

            assertThat(raw).isNotEmpty().allSatisfy(
                data -> assertThat(data).isInstanceOf(RawMessageStreamEvent.class));
            assertThat(rawBeforeFirstTextDelta).isTrue();
        }
    }

    @Test
    void noHooksMeansZeroFiringAndUnchangedWire() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(
                    200, Map.of("Content-Type", "text/event-stream"), SUCCESS_SSE));

            var events = runStream(options(server.baseUrl(), Map.of()));

            assertThat(server.body()).contains("\"max_tokens\":4096");
            assertThat(events).anyMatch(
                e -> e instanceof StreamEvent.StreamDone d && d.reason() == StopReason.STOP);
        }
    }
}
