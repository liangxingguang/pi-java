package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
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

    // ══════════════════════════════════════════════════════════════════
    // B20 提交 ⑦：α/β/γ/δ/ε 五条（pi openai-responses.ts:181-192 +
    // openai-responses-shared.ts:588-596/741-760/763-796）
    // ══════════════════════════════════════════════════════════════════

    /**
     * β + γ：{@code response.incomplete} 且 reason 非 {@code max_output_tokens} ⇒
     * **error 事件**（不是 done），文案 {@code "Response incomplete: X"}。
     *
     * <p>⚠️ 修复前的形状是「{@code StreamDone("error")} 且**没有**任何 error 事件」：
     * 映射只回裸 {@code "error"}（无文案），收尾又照发 done ⇒ 破「done = 成功」这条协议
     * 不变量，且转录取不到原因 —— 文案是重试分类器
     * （{@code RetryableError.isRetryableAssistantError}）的**唯一**输入。</p>
     */
    @Test
    void incompleteNonMaxReasonIsAnErrorNotDone() throws Exception {
        var api = api(startServer(incompleteSse("content_filter")));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events)).isEqualTo("Response incomplete: content_filter");
        assertThat(dones(events)).isEmpty();
        // 出错前已收到的文本仍要在通道上（pi 也是先 push 出去再 throw）
        assertThat(textDeltas(events)).containsExactly("Hi");
    }

    /** β（第二支）：{@code incomplete_details} 在但**没有** reason ⇒ pi 的专属兜底文案。 */
    @Test
    void incompleteWithoutReasonIsAnError() throws Exception {
        var api = api(startServer(incompleteSseWithoutReason()));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events))
            .isEqualTo("Response incomplete without a provider reason");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * α：线格给了 {@code mapStopReason} 不认识的 status ⇒ **throw**（pi 的 {@code default}）。
     *
     * <p>⚠️ 修复前 {@code default -> "stop"} 把「没见过的状态」当成**正常结束**发出去。
     * 这条能被线格触发是实测过的：openai-java 4.42.0 的 {@code ResponseStatus} 是
     * Enum 模式（{@code toString()} 给线格原值、{@code known()} 抛），未知值**照常反序列化**。</p>
     */
    @Test
    void unknownStatusIsAnError() throws Exception {
        var api = api(startServer(completedSseWithStatus("brand_new")));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events)).isEqualTo("Unhandled stop reason: brand_new");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * δ（第 1 支）：{@code response.failed} 带完整 error ⇒ {@code "code: message"}。
     *
     * <p>**对照面**：这一支修复前后**同文案**，留着是为了让另外三支的差异可读
     * （δ 的偏差全在退化路径上）。</p>
     */
    @Test
    void failedResponseWithErrorUsesCodeAndMessage() throws Exception {
        var api = api(startServer(failedSse()));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events)).isEqualTo("server_error: boom");
        assertThat(dones(events)).isEmpty();
    }

    /** δ（第 2 支）：{@code response.failed} 无 error、但有 {@code incomplete_details.reason}。 */
    @Test
    void failedResponseWithoutErrorUsesIncompleteReason() throws Exception {
        var api = api(startServer(failedSseWithIncompleteReason("content_filter")));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events)).isEqualTo("incomplete: content_filter");
        assertThat(dones(events)).isEmpty();
    }

    /** δ（第 3 支）：两样都没有 ⇒ {@code "Unknown error (no error details in response)"}。 */
    @Test
    void failedResponseWithoutDetailsUsesPiWording() throws Exception {
        var api = api(startServer(failedSseWithoutDetails()));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events))
            .isEqualTo("Unknown error (no error details in response)");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * δ（第 4 支）：error 里**只有** code ⇒ message 位置取 {@code "no message"}。
     *
     * <p>⚠️ 这一支同时钉住**读法**：openai-java 4.42.0 的 {@code ResponseError.message()}
     * 在字段缺席时**抛** {@code OpenAIInvalidDataException}（实测，见 §8.35.14 实施记录），
     * 直接读它就会把 pi 的兜底文案换成一条 SDK 异常文本。</p>
     */
    @Test
    void failedResponseWithoutErrorMessageIsNoMessage() throws Exception {
        var api = api(startServer(failedSseWithCodeOnly()));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events)).isEqualTo("server_error: no message");
        assertThat(dones(events)).isEmpty();
    }

    /**
     * ε：{@code error} 事件 ⇒ **终止整条流**（pi throw），不是「发一条 error 继续读」。
     *
     * <p>⚠️ 修复前出错后仍继续迭代 ⇒ 后面那帧文本照样发出去、结尾的 {@code completed}
     * 还会补一条 {@code done}。这条夹具在 error 之后故意放了一帧文本与一个终局事件，
     * 两样都不许出现。</p>
     */
    @Test
    void errorEventTerminatesTheStream() throws Exception {
        var api = api(startServer(errorEventSse()));

        var events = collectRaw(api, "hi");

        assertThat(errorMessage(events)).isEqualTo("Error Code rate_limit: slow down");
        assertThat(dones(events)).isEmpty();
        assertThat(textDeltas(events)).containsExactly("Hi");
    }

    @Test
    void failedResponseMapsToError() throws Exception {
        var baseUrl = startServer(failedSse());
        var api = api(baseUrl);
        assertHasError(collect(api, "hi"));
    }

    /**
     * 未终局的流 ⇒ error + {@code "…before a terminal response event"}。
     *
     * <p>**对照面**（{@code §8.35.14 第八节}）：文案修复前后相同；本提交只是把它从
     * 「就地 emitError」改成「throw 后由外层 catch 落成同一个 error 事件」，这条钉住
     * 该改写没有漏掉文案。</p>
     */
    @Test
    void streamEndingWithoutTerminalEventMapsToError() throws Exception {
        var baseUrl = startServer(noTerminalSse());
        var api = api(baseUrl);
        assertThat(errorMessage(collectRaw(api, "hi")))
            .isEqualTo("OpenAI Responses stream ended before a terminal response event");
    }

    // ── Request building ────────────────────────────────────────────────

    @Test
    void buildParamsClampsMaxOutputTokensTo16() throws Exception {
        var api = api("http://localhost:1/v1");
        var request = new StreamRequest(
            ModelId.of("openai", "gpt-4o"),
            null,
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 4, -1, Map.of());

        var method = ResponsesMessageConverter.class.getDeclaredMethod(
            "buildParams", StreamRequest.class, ResponsesOptions.class, String.class, String.class);
        method.setAccessible(true);
        var params = (com.openai.models.responses.ResponseCreateParams) method.invoke(
            null, request, ResponsesOptions.from(ApiOptions.defaults()), "gpt-4o", "openai-responses");
        assertThat(params.maxOutputTokens().orElseThrow()).isEqualTo(16);
    }

    @Test
    void buildParamsSetsReasoningEffortFromExtra() throws Exception {
        var request = new StreamRequest(
            ModelId.of("openai", "gpt-4o"),
            null,
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 100, -1, Map.of());
        var options = new ApiOptions("", "test-key", Duration.ofSeconds(10), 1,
            Map.of("reasoningEffort", "high", "reasoningSummary", "auto"));

        var method = ResponsesMessageConverter.class.getDeclaredMethod(
            "buildParams", StreamRequest.class, ResponsesOptions.class, String.class, String.class);
        method.setAccessible(true);
        var params = (com.openai.models.responses.ResponseCreateParams) method.invoke(
            null, request, ResponsesOptions.from(options), "gpt-4o", "openai-responses");
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

    /**
     * 收一条流的**全部**事件，不补终局事件。
     *
     * <p>⚠️ 不走 {@code streamBlocking}：它在 {@code onComplete} 时**无条件**补一条
     * {@code StreamDone("stop", …)}（{@code AbstractChatApi:107-116} 的兜底），
     * 「只有 error、没有 done」这类断言会被它污染。</p>
     */
    private List<StreamEvent> collectRaw(OpenAIResponsesApi api, String prompt) throws Exception {
        var request = StreamRequest.of(ModelId.of("openai", "gpt-4o"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent(prompt)))));
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

    private static List<StreamEvent> errors(List<StreamEvent> events) {
        return events.stream().filter(StreamEvent.StreamError.class::isInstance).toList();
    }

    private static List<StreamEvent> dones(List<StreamEvent> events) {
        return events.stream().filter(StreamEvent.StreamDone.class::isInstance).toList();
    }

    private static List<String> textDeltas(List<StreamEvent> events) {
        return events.stream().filter(StreamEvent.TextDelta.class::isInstance)
            .map(e -> ((StreamEvent.TextDelta) e).delta()).toList();
    }

    /**
     * 错误通道**唯一一条**的 {@code getMessage()}。
     *
     * <p>⚠️「有 error」与「**恰**一条 error」是两件事：pi 收尾处是 throw ⇒ 一条流只有
     * 一个终局事件；pi-java 若不记账就会「先 error 后 done」。故这一并钉上。</p>
     */
    private static String errorMessage(List<StreamEvent> events) {
        var errors = errors(events);
        assertThat(errors)
            .as("错误通道应恰有一条事件，实际事件：" + names(events))
            .hasSize(1);
        return ((StreamEvent.StreamError) errors.get(0)).error().getMessage();
    }

    /** 事件名，{@code StreamDone} 带上取值 —— 红灯里能直接读出线格被翻成了什么。 */
    private static List<String> names(List<StreamEvent> events) {
        return events.stream().map(e -> e instanceof StreamEvent.StreamDone d
            ? "StreamDone(" + d.reason() + ")"
            : e.getClass().getSimpleName()).toList();
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
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\"Hi\"}"),
            event("{\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"incomplete\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null,"
                + "\"incomplete_details\":{\"reason\":\"" + reason + "\"}}}"));
    }

    /** {@code incomplete_details} 在、但没有 reason（实测能反序列化成 present-无-reason）。 */
    private static String incompleteSseWithoutReason() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"incomplete\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null,"
                + "\"incomplete_details\":{}}}"));
    }

    /** 终局事件带一个 {@code mapStopReason} 不认识的 status（α）。 */
    private static String completedSseWithStatus(String status) {
        return sse(created("in_progress"), completedEmpty(status));
    }

    /** {@code response.failed}：无 error，但有 {@code incomplete_details.reason}（δ 第 2 支）。 */
    private static String failedSseWithIncompleteReason(String reason) {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"failed\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null,"
                + "\"incomplete_details\":{\"reason\":\"" + reason + "\"}}}"));
    }

    /** {@code response.failed}：error 与 incomplete_details 都没有（δ 第 3 支）。 */
    private static String failedSseWithoutDetails() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"failed\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}"));
    }

    /** {@code response.failed}：error 里只有 code（δ 第 4 支）。 */
    private static String failedSseWithCodeOnly() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"failed\",\"model\":\"gpt-4o\",\"output\":[],"
                + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null,"
                + "\"error\":{\"code\":\"server_error\"}}}"));
    }

    /**
     * {@code error} 事件之后**还有**内容与终局事件（ε）。
     *
     * <p>那两帧是故意的：pi 在 error 处 throw ⇒ 它们**一帧都不该**出现；修复前会照发。</p>
     */
    private static String errorEventSse() {
        return sse(
            created("in_progress"),
            event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"in_progress\",\"content\":[]}}"),
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\"Hi\"}"),
            event("{\"type\":\"error\",\"code\":\"rate_limit\",\"message\":\"slow down\","
                + "\"sequence_number\":1}"),
            event("{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"delta\":\" AFTER-ERROR\"}"),
            completedEmpty("completed"));
    }

    /** 终局事件（{@code response.completed}），{@code output} 为空数组。 */
    private static String completedEmpty(String status) {
        return event("{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"" + status + "\",\"model\":\"gpt-4o\",\"output\":[],"
            + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}");
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
