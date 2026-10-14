package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.AllowedFallbackModel;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.CostCalculator;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B148：anthropic 车道的流内字段（对位 pi
 * {@code anthropic-sse-parsing.test.ts:142-243/:317-357}）：
 * <ol>
 *   <li>message_start 报不同模型 ⇒ responseModel，model/provider 不变；</li>
 *   <li>不同模型且命中 allowedFallbackModels ⇒ 成本按条目 cost 归属；</li>
 *   <li>已有内容之后来 "fallback" 块 ⇒ 硬错（原文 errorMessage，块保留）；</li>
 *   <li>无内容时的 "fallback" 块 ⇒ 跳过，流正常收束；</li>
 *   <li>input_transformations start 一份、delta 一份 ⇒ 诊断只留末值。</li>
 * </ol>
 */
class AnthropicResponseModelWireTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 代理改标模型：responseModel 落消息，model 仍是请求 id。 */
    @Test
    void proxiedModelLandsOnResponseModel() throws Exception {
        var body = textStream("kimi-for-coding", "end_turn", null, "hello");
        var events = collectWithModelId(body, "claude-sonnet-4");

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.partial().model()).isEqualTo("claude-sonnet-4");
        assertThat(done.partial().responseModel()).isEqualTo("kimi-for-coding");
    }

    /** 命中许可回退条目：cost 用条目定价（pi :671-677 的 usageModel 切换）。 */
    @Test
    void fallbackEntryRepricesTheUsage() throws Exception {
        var model = BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", "claude-fable-5")).orElseThrow();
        AllowedFallbackModel fallback = model.compat().allowedFallbackModels().stream()
            .filter(f -> f.model().equals("claude-opus-5"))
            .findFirst().orElseThrow();

        var body = textStream("claude-opus-5", "end_turn", null, "done");
        var events = collectWithModel(body, model);
        var done = last(events, StreamEvent.StreamDone.class);

        assertThat(done.partial().responseModel()).isEqualTo("claude-opus-5");
        var usage = done.partial().usage().usage();
        assertThat(usage.cost().total())
            .as("成本按 fallback 条目的 cost 归属")
            .isEqualByComparingTo(
                CostCalculator.calculateCost(fallback.cost(), usage).total());
        assertThat(usage.cost().total())
            .as("fallback 定价与请求模型定价不同（夹具有效性守卫）")
            .isNotEqualByComparingTo(
                CostCalculator.calculateCost(model.pricing(), usage).total());
    }

    /** 已有内容之后的 fallback 块：硬错，errorMessage 逐字，已流内容保留。 */
    @Test
    void midOutputFallbackAfterContentFails() throws Exception {
        var body = sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_fallback\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"partial\"}}")
            + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"fallback\","
                + "\"from\":{\"model\":\"claude-sonnet-4\"},"
                + "\"to\":{\"model\":\"claude-opus-5\"}}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
        var events = collectWithModelId(body, "claude-sonnet-4");

        var error = last(events, StreamEvent.StreamError.class);
        assertThat(error.partial().stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(StreamEvent.StreamError.textOf(error))
            .contains("unsupported mid-output model fallback");
        assertThat(error.partial().content())
            .as("已流出的文本块保留")
            .hasSize(1)
            .first()
            .isInstanceOf(ContentBlock.TextContent.class);
    }

    /** 无已有内容时的 fallback 块：pi 的 continue（不建块、不发事件，responseModel 已盖）。 */
    @Test
    void earlyFallbackBlockIsSkipped() throws Exception {
        var body = sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_x\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"served-by-fallback\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"fallback\"}}")
            + sse("message_delta", "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":2}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
        var events = collectWithModelId(body, "claude-sonnet-4");

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo(StopReason.STOP);
        assertThat(done.partial().content()).isEmpty();
        assertThat(done.partial().responseModel()).isEqualTo("served-by-fallback");
    }

    /** input_transformations：start 一份、delta 一份 ⇒ 诊断只见末值（数组替换，不是追加）。 */
    @Test
    void diagnosticUsesTheFinalTransformationsList() throws Exception {
        var body = sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_t\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-fable-5-1\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":0},"
                + "\"input_transformations\":["
                + "{\"type\":\"thinking_dropped\",\"path\":\"messages.1.content.0\","
                + "\"reason\":\"prefix_binding_mismatch\"}]}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
            + sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}")
            + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + sse("message_delta", "{\"type\":\"message_delta\","
                + "\"input_transformations\":["
                + "{\"type\":\"thinking_dropped\",\"path\":\"messages.3.content.0\","
                + "\"reason\":\"model_binding_mismatch\"}],"
                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":2}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
        var events = collectWithModelId(body, "claude-fable-5-1");

        var done = last(events, StreamEvent.StreamDone.class);
        var diagnostics = done.partial().diagnostics();
        assertThat(diagnostics).hasSize(1);
        var diagnostic = diagnostics.get(0);
        assertThat(diagnostic.type()).isEqualTo("anthropic_input_transformations");
        assertThat(diagnostic.error()).isNull();
        var transformations = diagnostic.details().get("transformations");
        assertThat(transformations).hasSize(1);
        assertThat(transformations.path(0).path("path").asText())
            .isEqualTo("messages.3.content.0");
        assertThat(transformations.path(0).path("reason").asText())
            .isEqualTo("model_binding_mismatch");
    }

    /** 无变换的正常流不产出诊断。 */
    @Test
    void noTransformationsNoDiagnostic() throws Exception {
        var body = textStream("claude-sonnet-4", "end_turn", null, "hi");
        var events = collectWithModelId(body, "claude-sonnet-4");

        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.partial().diagnostics()).isNull();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private List<StreamEvent> collectWithModelId(String sseBody, String requestedModel)
            throws Exception {
        var model = BuiltinModelCatalogs.catalog("anthropic")
            .find(ModelId.of("anthropic", requestedModel))
            .orElseGet(() -> com.pijava.ai.catalog.ModelInfo.minimal(
                ModelId.of("anthropic", requestedModel)));
        return collectWithModel(sseBody, model);
    }

    private List<StreamEvent> collectWithModel(String sseBody,
                                                com.pijava.ai.catalog.ModelInfo model)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] bodyBytes = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bodyBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bodyBytes);
            }
        });
        server.start();
        var api = new AnthropicMessagesApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort(), "test-key",
            Duration.ofSeconds(5), 0, Map.of()));

        List<Message> messages = List.of(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi"))));
        var request = new StreamRequest(model, "be brief", messages, List.of(), -1, -1);

        var events = new CopyOnWriteArrayList<StreamEvent>();
        var finished = new CountDownLatch(1);
        api.stream(request, ApiOptions.defaults()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent e) {
                events.add(e);
            }
            @Override public void onError(Throwable t) {
                finished.countDown();
            }
            @Override public void onComplete() {
                finished.countDown();
            }
        });
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        return List.copyOf(events);
    }

    /** 一条带文本块的正常流；message_start.model 可换。 */
    private static String textStream(String startModel, String stopReason,
                                      String unused, String text) {
        return sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"" + startModel + "\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":100000,\"output_tokens\":0}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
            + sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}")
            + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + sse("message_delta", "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":1000}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static String sse(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast)
            .reduce((a, b) -> b).orElseThrow();
    }
}
