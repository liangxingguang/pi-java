package com.pijava.ai.protocol;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.6.0（pi {@code 03de604b8}，{@code openai-responses-shared.ts:629-650}）：
 * 终局响应的 {@code input_transformations} 里被 API 丢弃的重放 reasoning 项
 * 落成 {@code openai_input_transformations} 诊断。
 *
 * <p>⚠️ {@code turn_boundary} 类的移除<b>不记录</b>（GPT-5.6 之前的模型每轮都报，
 * 记下来会让会话按对话长度二次增长）；非 reasoning、非 removed 的项同样跳过。</p>
 */
class ResponsesInputTransformationsWireTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reasoningRemovalBecomesADiagnostic() throws Exception {
        var done = lastDone(collect(sse("""
            [{"type":"reasoning","id":"rs_1","action":"removed","reason":"prefix_mismatch"}]
            """.trim())));

        assertThat(done.partial().diagnostics()).hasSize(1);
        var diagnostic = done.partial().diagnostics().get(0);
        assertThat(diagnostic.type()).isEqualTo("openai_input_transformations");
        assertThat(diagnostic.error()).isNull();
        var transformations = diagnostic.details().get("transformations");
        assertThat(transformations).hasSize(1);
        assertThat(transformations.path(0).path("type").asText()).isEqualTo("reasoning");
        assertThat(transformations.path(0).path("id").asText()).isEqualTo("rs_1");
        assertThat(transformations.path(0).path("action").asText()).isEqualTo("removed");
        assertThat(transformations.path(0).path("reason").asText()).isEqualTo("prefix_mismatch");
    }

    @Test
    void turnBoundaryRemovalsAreLeftOut() throws Exception {
        var done = lastDone(collect(sse("""
            [{"type":"reasoning","id":"rs_1","action":"removed","reason":"turn_boundary"}]
            """.trim())));

        assertThat(done.partial().diagnostics()).isNull();
    }

    @Test
    void nonReasoningEntriesAreLeftOut() throws Exception {
        var done = lastDone(collect(sse("""
            [{"type":"message","id":"m_1","action":"removed","reason":"prefix_mismatch"}]
            """.trim())));

        assertThat(done.partial().diagnostics()).isNull();
    }

    @Test
    void nonRemovalActionsAreLeftOut() throws Exception {
        var done = lastDone(collect(sse("""
            [{"type":"reasoning","id":"rs_1","action":"changed","reason":"prefix_mismatch"}]
            """.trim())));

        assertThat(done.partial().diagnostics()).isNull();
    }

    @Test
    void aNonArrayFieldIsTolerated() throws Exception {
        // 老服务器/代理省略字段；Codex 连畸形条目也容忍 ⇒ 非数组一律当无。
        var done = lastDone(collect(sse("\"not-an-array\"")));

        assertThat(done.partial().diagnostics()).isNull();
    }

    @Test
    void malformedEntriesInsideTheArrayAreSkipped() throws Exception {
        var done = lastDone(collect(sse("""
            [null, 42, {"type":"reasoning","id":"rs_1","action":"removed","reason":"prefix_mismatch"}]
            """.trim())));

        assertThat(done.partial().diagnostics()).hasSize(1);
        assertThat(done.partial().diagnostics().get(0).details()
            .get("transformations")).hasSize(1);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static StreamEvent.StreamDone lastDone(List<StreamEvent> events) {
        return events.stream()
            .filter(StreamEvent.StreamDone.class::isInstance)
            .map(StreamEvent.StreamDone.class::cast)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("流里没有 StreamDone：" + events));
    }

    /** created(in_progress) + completed（带 {@code input_transformations}）。 */
    private static String sse(String transformationsJson) {
        return "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"in_progress\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"completed\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],"
                + "\"input_transformations\":" + transformationsJson + "}}\n\n"
            + "data: [DONE]\n\n";
    }

    private static final ModelInfo MODEL = new ModelInfo(
        ModelId.of("openai", "gpt-4o"), "gpt-4o",
        Set.of(ModelCapability.TEXT), 128_000, 16_384, false,
        new PricingInfo(3.0, 15.0, 0.3, 3.75, List.of()));

    private List<StreamEvent> collect(String sseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        var api = new OpenAIResponsesApi(new ApiOptions(
            "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key",
            Duration.ofSeconds(5), 0, Map.of()), "OPENAI_API_KEY");

        var request = new StreamRequest(MODEL, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), -1, -1);

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
        assertThat(finished.await(10, TimeUnit.SECONDS)).as("流未在 10s 内结束").isTrue();
        return List.copyOf(events);
    }
}
