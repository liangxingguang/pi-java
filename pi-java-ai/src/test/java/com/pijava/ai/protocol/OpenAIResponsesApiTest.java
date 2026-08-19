package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-1e: OpenAIResponsesApi — 事件映射（本地 HTTP server 驱动真实 SSE 路径）、
 * stopReason 分支、{@code max_output_tokens} clamp。
 */
class OpenAIResponsesApiTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void textFlowEmitsTextEventsThenDone() throws Exception {
        var baseUrl = startServer(textSse());
        var api = api(baseUrl);

        List<StreamEvent> events = collect(api, "hi");
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.TextStart.class, StreamEvent.TextDelta.class,
            StreamEvent.TextDelta.class, StreamEvent.TextEnd.class,
            StreamEvent.UsageInfo.class, StreamEvent.StreamDone.class);
        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("stop");
        var text = done.partial().content().stream()
            .filter(b -> b instanceof ContentBlock.TextContent)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .reduce("", String::concat);
        assertThat(text).isEqualTo("Hello world");
    }

    @Test
    void toolCallFlowEmitsToolEventsAndToolUseDone() throws Exception {
        var baseUrl = startServer(toolCallSse());
        var api = api(baseUrl);

        List<StreamEvent> events = collect(api, "weather?");
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.ToolCallStart.class, StreamEvent.ToolCallDelta.class,
            StreamEvent.ToolCallDelta.class, StreamEvent.ToolCallEnd.class,
            StreamEvent.StreamDone.class);
        var end = last(events, StreamEvent.ToolCallEnd.class);
        assertThat(end.name()).isEqualTo("get_weather");
        assertThat(end.arguments()).containsEntry("city", "Beijing");
        var done = last(events, StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("tool_use");
    }

    @Test
    void thinkingFlowEmitsThinkingEvents() throws Exception {
        var baseUrl = startServer(thinkingSse());
        var api = api(baseUrl);

        List<StreamEvent> events = collect(api, "think");
        // pi 行为：summary_text.delta ×2 → delta；summary_part.done → delta("\n\n")，
        // 最后 output_item.done(reasoning) → ThinkingEnd。
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.ThinkingStart.class, StreamEvent.ThinkingDelta.class,
            StreamEvent.ThinkingDelta.class, StreamEvent.ThinkingDelta.class,
            StreamEvent.ThinkingEnd.class, StreamEvent.TextStart.class,
            StreamEvent.TextDelta.class, StreamEvent.TextEnd.class,
            StreamEvent.StreamDone.class);
    }

    @Test
    void refusalDeltaMergesIntoTextChannel() throws Exception {
        var baseUrl = startServer(refusalSse());
        var api = api(baseUrl);
        List<StreamEvent> events = collect(api, "hi");
        var done = last(events, StreamEvent.StreamDone.class);
        var text = done.partial().content().stream()
            .filter(b -> b instanceof ContentBlock.TextContent)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .reduce("", String::concat);
        assertThat(text).isEqualTo("I refuse");
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.TextStart.class, StreamEvent.TextDelta.class,
            StreamEvent.TextDelta.class, StreamEvent.TextEnd.class,
            StreamEvent.StreamDone.class);
    }

    @Test
    void reasoningTextDeltaEmitsThinkingDelta() throws Exception {
        var baseUrl = startServer(reasoningTextSse());
        var api = api(baseUrl);
        List<StreamEvent> events = collect(api, "hi");
        assertEventSequence(events, StreamEvent.Start.class,
            StreamEvent.ThinkingStart.class, StreamEvent.ThinkingDelta.class,
            StreamEvent.ThinkingEnd.class, StreamEvent.StreamDone.class);
    }

    @Test
    void maxOutputTokensIncompleteMapsToLength() throws Exception {
        var baseUrl = startServer(incompleteSse("max_output_tokens"));
        var api = api(baseUrl);
        var done = last(collect(api, "hi"), StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("length");
    }

    @Test
    void incompleteWithoutProviderReasonMapsToError() throws Exception {
        var baseUrl = startServer(incompleteSse("content_filter"));
        var api = api(baseUrl);
        var done = last(collect(api, "hi"), StreamEvent.StreamDone.class);
        assertThat(done.reason()).isEqualTo("error");
    }

    @Test
    void failedResponseMapsToError() throws Exception {
        var baseUrl = startServer(failedSse());
        var api = api(baseUrl);
        assertHasError(collect(api, "hi"));
    }

    @Test
    void streamEndingWithoutTerminalEventMapsToError() throws Exception {
        var baseUrl = startServer(noTerminalSse());
        var api = api(baseUrl);
        assertHasError(collect(api, "hi"));
    }

    // ── Request building ────────────────────────────────────────────────

    @Test
    void buildParamsClampsMaxOutputTokensTo16() throws Exception {
        var api = api("http://localhost:1/v1");
        var request = new StreamRequest(
            ModelId.of("openai", "gpt-4o"),
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 4, -1, Map.of());

        var method = ResponsesMessageConverter.class.getDeclaredMethod(
            "buildParams", StreamRequest.class, ResponsesOptions.class);
        method.setAccessible(true);
        var params = (com.openai.models.responses.ResponseCreateParams) method.invoke(
            null, request, ResponsesOptions.from(ApiOptions.defaults()));
        assertThat(params.maxOutputTokens().orElseThrow()).isEqualTo(16);
    }

    @Test
    void buildParamsSetsReasoningEffortFromExtra() throws Exception {
        var request = new StreamRequest(
            ModelId.of("openai", "gpt-4o"),
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 100, -1, Map.of());
        var options = new ApiOptions("", "test-key", Duration.ofSeconds(10), 1,
            Map.of("reasoningEffort", "high", "reasoningSummary", "auto"));

        var method = ResponsesMessageConverter.class.getDeclaredMethod(
            "buildParams", StreamRequest.class, ResponsesOptions.class);
        method.setAccessible(true);
        var params = (com.openai.models.responses.ResponseCreateParams) method.invoke(
            null, request, ResponsesOptions.from(options));
        var reasoning = params.reasoning().orElseThrow();
        assertThat(reasoning.effort().orElseThrow().toString()).isEqualTo("high");
        assertThat(reasoning.summary().orElseThrow().toString()).isEqualTo("auto");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private OpenAIResponsesApi api(String baseUrl) {
        return new OpenAIResponsesApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(10), 0, Map.of()),
            "OPENAI_API_KEY");
    }

    private List<StreamEvent> collect(OpenAIResponsesApi api, String prompt) {
        var events = new ArrayList<StreamEvent>();
        var request = StreamRequest.of(ModelId.of("openai", "gpt-4o"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent(prompt)))));
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }
        return events;
    }

    private String startServer(String sseBody) throws IOException {
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
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    private static void assertEventSequence(List<StreamEvent> events,
                                            Class<?>... expected) {
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
            .containsExactly(java.util.Arrays.stream(expected)
                .map(Class::getSimpleName).toArray(String[]::new));
    }

    private static <T extends StreamEvent> T last(List<StreamEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance)
            .map(type::cast).reduce((a, b) -> b).orElseThrow();
    }

    private static void assertHasError(List<StreamEvent> events) {
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
    }

    // ── SSE fixtures ────────────────────────────────────────────────────

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String sse(String... events) {
        return String.join("", events) + "data: [DONE]\n\n";
    }

    private static String created(String status) {
        return event("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"" + status + "\",\"model\":\"gpt-4o\",\"output\":[],"
            + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}");
    }

    private static String textSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"in_progress\",\"content\":[]}}"),
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\"Hello\"}"),
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\" world\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"Hello world\",\"annotations\":[]}]}}"),
            completed("completed",
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"Hello world\",\"annotations\":[]}]}",
                "{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,"
                + "\"input_tokens_details\":{\"cached_tokens\":0},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":0}}"));
    }

    private static String toolCallSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\","
                + "\"status\":\"in_progress\",\"call_id\":\"call_1\","
                + "\"name\":\"get_weather\",\"arguments\":\"\"}}"),
            event("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"delta\":\"{\\\"city\\\":\\\"Beij\"}"),
            event("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"delta\":\"ing\\\"}\"}"),
            event("{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\","
                + "\"output_index\":0,\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\","
                + "\"status\":\"completed\",\"call_id\":\"call_1\","
                + "\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}}"),
            completed("completed",
                "{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\","
                + "\"call_id\":\"call_1\",\"name\":\"get_weather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}",
                null));
    }

    private static String thinkingSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"in_progress\","
                + "\"summary\":[],\"content\":[]}}"),
            event("{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\","
                + "\"output_index\":0,\"delta\":\"Let me\"}"),
            event("{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\","
                + "\"output_index\":0,\"delta\":\" think\"}"),
            event("{\"type\":\"response.reasoning_summary_part.done\",\"item_id\":\"rs_1\","
                + "\"output_index\":0,\"summary\":[{\"type\":\"summary_text\","
                + "\"text\":\"Let me think\"}]}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"Let me think\"}],"
                + "\"content\":[]}}"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":1,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"in_progress\",\"content\":[]}}"),
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":1,\"delta\":\"Answer\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":1,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"Answer\",\"annotations\":[]}]}}"),
            completed("completed",
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"Answer\",\"annotations\":[]}]}",
                null));
    }

    private static String refusalSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"in_progress\",\"content\":[]}}"),
            event("{\"type\":\"response.refusal.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\"I\"}"),
            event("{\"type\":\"response.refusal.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\" refuse\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"refusal\","
                + "\"refusal\":\"I refuse\"}]}}"),
            completed("completed",
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"refusal\","
                + "\"refusal\":\"I refuse\"}]}",
                null));
    }

    private static String reasoningTextSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"in_progress\","
                + "\"summary\":[],\"content\":[]}}"),
            event("{\"type\":\"response.reasoning_text.delta\",\"item_id\":\"rs_1\","
                + "\"output_index\":0,\"delta\":\"inner\"}"),
            event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"inner\"}],"
                + "\"content\":[]}}"),
            completed("completed",
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"\",\"annotations\":[]}]}",
                null));
    }

    private static String incompleteSse(String reason) {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"in_progress\",\"content\":[]}}"),
            event("{\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"incomplete\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null,"
                + "\"incomplete_details\":{\"reason\":\"" + reason + "\"}}}"));
    }

    private static String failedSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"failed\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null,"
                + "\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}"));
    }

    private static String noTerminalSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"in_progress\",\"content\":[]}}"),
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\"Hi\"}"));
    }

    private static String completed(String status, String outputItem, String usage) {
        var usageJson = usage == null ? "null" : usage;
        return event("{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"" + status + "\",\"model\":\"gpt-4o\",\"output\":["
            + outputItem + "],\"parallel_tool_calls\":true,\"tools\":[],"
            + "\"usage\":" + usageJson + "}}");
    }
}
