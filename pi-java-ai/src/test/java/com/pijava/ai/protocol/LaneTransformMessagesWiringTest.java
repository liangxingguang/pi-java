package com.pijava.ai.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.utils.ShortHash;

import com.openai.models.chat.completions.ChatCompletionMessageParam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>B10 的**接线**夹具</b>（docs/31 §8.35.4 / §8.35.5-1）—— 每一条**车道**上都必须跑
 * 共享预通道 {@link com.pijava.ai.api.TransformMessages}。
 *
 * <p>与 {@code TransformMessagesTest} 的**分工必须说清**：那一类只证明**闸本身**的行为
 * （给定输入得到什么输出），它**看不见接线** —— 把某条车道的构造点改回不过闸，它照样全绿。
 * 本类观测的是**真正发到线上的请求体**（HTTP 桩录制），所以它是唯一能守住
 * 「六条车道都挂了闸」这件事的夹具（pi：`transform-messages.ts` 被六个请求构建器调用，
 * `anthropic-messages.ts:1029` / `openai-completions.ts:1212` / `openai-responses-shared.ts:172` /
 * `google-shared.ts:138` / `mistral-conversations.ts:139` / `bedrock-converse-stream.ts:935`）。</p>
 *
 * <p><b>为什么用 HTTP 桩而不是反射私有构建器</b>：反射要绑死每个车道构建器的形参表，
 * 而本次改动**正要给它们加形参**（api 名）⇒ 夹具会在实现落地的瞬间编译不过，
 * 于是「先红证毕」变成编译期红、看不到行为红。桩录制的是**请求字节**，
 * 形参怎么改都不影响它，且它证的是端到端（消息 → 预通道 → 落线 → 线格）。</p>
 *
 * <p>判据取自 pi：跨模型重放时带签名的 thinking 块**降级为 text**（`transform-messages.ts:113-116`）
 * ⇒ 该文本必须**出现在请求体里**（作为文本），而不是消失。</p>
 */
class LaneTransformMessagesWiringTest {

    /** 跨模型来源的助手消息：anthropic 身份 + 带签名的 thinking 块 + 可见文本。 */
    private static final String FOREIGN_TEXT = "SECRET-REASONING";

    @Test
    void completionsLaneNormalizesCrossModelToolIds() throws Exception {
        var target = ModelId.of("openai", "gpt-4o");
        var id = "c".repeat(30) + "|" + "f".repeat(30);
        var toolUse = new ContentBlock.ToolUseContent(id, "read", Map.of());
        var result = new Message.ToolResultMessage(id, "read",
            List.of(new ContentBlock.TextContent("ok")), false);
        var request = new StreamRequest(target, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
                new Message.AssistantMessage(List.of(toolUse), "stop", null,
                    "anthropic-messages", "anthropic", "claude-x", null, null, null, null), result),
            List.of(), -1, -1, Map.of());

        var params = OpenAICompletionsMessageConverter.buildParams(request,
            "openai-completions", null);
        var assistant = params.messages().stream().filter(ChatCompletionMessageParam::isAssistant)
            .map(ChatCompletionMessageParam::asAssistant).findFirst().orElseThrow();
        var normalized = assistant.toolCalls().orElseThrow().get(0).asFunction().id();
        var expected = "c".repeat(30) + "_" + ShortHash.of(id).substring(0, 8);
        assertThat(normalized).isEqualTo(expected);
        var tool = params.messages().stream().filter(ChatCompletionMessageParam::isTool)
            .map(ChatCompletionMessageParam::asTool).findFirst().orElseThrow();
        assertThat(tool.toolCallId()).isEqualTo(normalized);
    }

    @Test
    void anthropicAdapterConverterNormalizesToolIds() throws Exception {
        var target = ModelId.of("anthropic", "claude-sonnet-5");
        var id = "call|illegal";
        var request = toolRequest(target, id);
        var method = AnthropicMessagesApi.class.getDeclaredMethod("buildParams", StreamRequest.class);
        method.setAccessible(true);
        var options = new ApiOptions("https://example.test", "test-key", Duration.ofSeconds(1), 0, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        var params = (com.anthropic.models.messages.MessageCreateParams) method.invoke(api, request);
        var block = params.messages().get(1).content().asBlockParams().stream()
            .filter(p -> p.isToolUse()).findFirst().orElseThrow().asToolUse();
        assertThat(block.id()).isEqualTo("call_illegal");
    }

    @Test
    void completionsLaneDowngradesCrossModelThinkingToText() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            drainQuietly(() -> api.streamBlocking(
                request(ModelId.of("openai", "gpt-4o")), ApiOptions.defaults()));

            // 今天：thinking 块被 addAssistantMessage 收进 reasoning，而 :235 的 provider 门
            // （"deepseek"）拦下了它 ⇒ 文本不出现在请求体里。
            assertThat(server.body()).contains(FOREIGN_TEXT);
        }
    }

    @Test
    void googleApiGatedModelNormalizesToolIdOnHttpPath() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new GoogleGenerativeAiApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            drainQuietly(() -> api.streamBlocking(
                toolRequest(ModelId.of("google", "gemini-3-pro"), "call_123|fc_123"),
                ApiOptions.defaults()));
            assertThat(server.body()).contains("\"id\":\"call_123_fc_123\"");
        }
    }

    @Test
    void googleApiNonGatedModelOmitsToolIdOnHttpPath() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new GoogleGenerativeAiApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            drainQuietly(() -> api.streamBlocking(
                toolRequest(ModelId.of("google", "gemini-2.5-pro"), "call_123|fc_123"),
                ApiOptions.defaults()));
            assertThat(server.body()).doesNotContain("\"id\":\"call_123_fc_123\"");
        }
    }

    @Test
    void googleLaneLeavesNonGatedModelIdUnchanged() {
        var target = ModelId.of("google", "gemini-2.5-pro");
        var request = toolRequest(target, "call_123|fc_123");
        var contents = GoogleMessageConverter.toContents(
            com.pijava.ai.api.TransformMessages.apply(request.messages(), request.modelId(),
                "google-generative-ai", request.model(), GoogleToolCallIds.create()),
            request.modelId(), request.model());
        var functionCall = contents.get(1).parts().orElseThrow().get(0).functionCall().orElseThrow();
        assertThat(functionCall.id()).isEmpty();
    }


    @Test
    void openAiResponsesAdapterUsesOpenAiApiNameForToolId() throws Exception {
        var body = captureResponsesBody("openai-responses", false, ModelId.of("openai", "gpt-4o"));
        assertThat(body).contains("\"call_id\":\"call_123|fc_abc\"");
    }

    @Test
    void azureResponsesAdapterUsesAzureApiNameForToolId() throws Exception {
        var body = captureResponsesBody("azure-openai-responses", true,
            ModelId.of("openai", "gpt-4o"));
        assertThat(body).contains("\"call_id\":\"call_123|fc_abc\"");
    }

    private static String captureResponsesBody(String apiName, boolean azure, ModelId<?> target)
            throws Exception {
        try (var server = new RecordingServer()) {
            var api = azure
                ? new AzureOpenAIResponsesApi(new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0,
                    Map.of("azureBaseUrl", server.baseUrl())), "AZURE_OPENAI_API_KEY")
                : new OpenAIResponsesApi(new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                    "OPENAI_API_KEY");
            drainQuietly(() -> api.streamBlocking(responsesToolRequest(target, apiName), ApiOptions.defaults()));
            return server.body();
        }
    }

    @Test
    void mistralApiNormalizesCollisionAndResetsOnEachRequest() throws Exception {
        var target = ModelId.of("mistral", "mistral-large");
        try (var server = new RecordingServer()) {
            var api = new MistralConversationsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            drainQuietly(() -> api.streamBlocking(toolRequest(target, "abcdefgh1"), ApiOptions.defaults()));
            var first = toolIdFromBody(server.body());
            drainQuietly(() -> api.streamBlocking(toolRequest(target, "abc-defgh1"), ApiOptions.defaults()));
            var second = toolIdFromBody(server.body());
            assertThat(first).isEqualTo("abcdefgh1");
            assertThat(second).hasSize(9);
        }
    }

    private static String toolIdFromBody(String body) {
        int start = body.indexOf("tool_call_id");
        if (start < 0) {
            return "";
        }
        start = body.indexOf('"', start + 13) + 1;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static StreamRequest responsesToolRequest(ModelId<?> target, String apiName) {
        var source = new Message.AssistantMessage(List.of(
            new ContentBlock.ToolUseContent("call_123|abc", "read", Map.of())), "stop", null,
            apiName, target.provider(), "previous-model", null, null, null, null);
        return new StreamRequest(target, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), source),
            List.of(), -1, -1, Map.of());
    }

    private static StreamRequest toolRequest(ModelId<?> target, String id) {
        var assistant = new Message.AssistantMessage(List.of(
            new ContentBlock.ToolUseContent(id, "read", Map.of())), "stop", null,
            "foreign", "foreign", "foreign-model", null, null, null, null);
        return new StreamRequest(target, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), assistant,
                new Message.ToolResultMessage(id, "read", List.of(new ContentBlock.TextContent("ok")), false)),
            List.of(), -1, -1, Map.of());
    }

    @Test
    void googleLaneDowngradesCrossModelThinkingToText() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new GoogleGenerativeAiApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            drainQuietly(() -> api.streamBlocking(
                request(ModelId.of("google", "gemini-2.5-pro")), ApiOptions.defaults()));

            // 今天：GoogleMessageConverter.blockParts 的 ThinkingContent 分支返回 List.of()（静默丢弃）。
            assertThat(server.body()).contains(FOREIGN_TEXT);
        }
    }

    @Test
    void mistralLaneDowngradesCrossModelThinkingToText() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new MistralConversationsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            drainQuietly(() -> api.streamBlocking(
                request(ModelId.of("mistral", "mistral-large")), ApiOptions.defaults()));

            // 今天：extractText 只收 TextContent ⇒ thinking 块无声消失。
            assertThat(server.body()).contains(FOREIGN_TEXT);
        }
    }

    @Test
    void responsesLaneDowngradesCrossModelThinkingToText() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new OpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            drainQuietly(() -> api.streamBlocking(
                request(ModelId.of("openai", "gpt-4o")), ApiOptions.defaults()));

            // 今天：ResponsesMessageConverter:180 注释写明「v1 不回放 ThinkingContent」⇒ 丢弃。
            assertThat(server.body()).contains(FOREIGN_TEXT);
        }
    }

    /**
     * Azure 与 OpenAI 的 responses 车道**共用** {@code ResponsesMessageConverter}，但
     * {@code apiName()} 不同（{@code "azure-openai-responses"}）—— 闸的同模型判据要比这个字符串，
     * 所以两条车道各需一条夹具：只测一条，另一条的 apiName 传错（例如图省事共用常量）
     * 就没人拦得住。
     */
    @Test
    void azureResponsesLaneDowngradesCrossModelThinkingToText() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new AzureOpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "AZURE_OPENAI_API_KEY");
            drainQuietly(() -> api.streamBlocking(
                request(ModelId.of("azure", "gpt-4o")), ApiOptions.defaults()));

            assertThat(server.body()).contains(FOREIGN_TEXT);
        }
    }

    /**
     * <b>回归门</b>：同模型重放**仍须**把 thinking 发出去（completions 车道的 deepseek 路径，
     * `OpenAICompletionsApi:235`）。
     *
     * <p>它同时钉住一件容易搞错的事：传给闸的 api 名必须**逐字等于**适配器写进消息的
     * {@code api} 字段（{@code "openai-completions"}）—— 传成别的（或 null）会让同模型判成异模型，
     * 文本被降级、字段名丢失，而上面四条「降级」夹具**照样全绿**。</p>
     */
    @Test
    void sameModelReplayStillCarriesThinking() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            var sameModel = new Message.AssistantMessage(
                List.of(new ContentBlock.ThinkingContent(FOREIGN_TEXT, "reasoning_content", false),
                    new ContentBlock.TextContent("VISIBLE")),
                "stop", null, "openai-completions", "deepseek", "deepseek-v4-flash",
                null, null, null, null);
            var request = new StreamRequest(ModelId.of("deepseek", "deepseek-v4-flash"), null,
                List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
                    sameModel),
                List.of(), -1, -1, Map.of());
            drainQuietly(() -> api.streamBlocking(request, ApiOptions.defaults()));

            assertThat(server.body()).contains(FOREIGN_TEXT);
        }
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private static StreamRequest request(ModelId<?> target) {
        var foreign = new Message.AssistantMessage(
            List.of(new ContentBlock.ThinkingContent(FOREIGN_TEXT, "sig-foreign", false),
                new ContentBlock.TextContent("VISIBLE")),
            "stop", null, "anthropic-messages", "anthropic", "claude-x", null, null, null, null);
        return new StreamRequest(target, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), foreign),
            List.of(), -1, -1, Map.of());
    }

    /** 消费整条流并**吞掉**异常 —— 桩回 400，请求体已经录到了，流怎么结束与本类无关。 */
    private static void drainQuietly(java.util.function.Supplier<com.pijava.ai.api.StreamIterator> s) {
        try (var iter = s.get()) {
            var events = new ArrayList<StreamEvent>();
            while (iter.hasNext() && events.size() < 100) {
                events.add(iter.next());
            }
        } catch (Exception ignored) {
            // 桩只负责录制请求体
        }
    }

    /** 录制**最后一次请求体**的桩服务器：根上下文接所有路径，回 400 让车道尽快收场。 */
    private static final class RecordingServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>("");

        RecordingServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (InputStream in = exchange.getRequestBody();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                body.set(out.toString(StandardCharsets.UTF_8));
            }
            byte[] payload = "{\"error\":{\"message\":\"recorded\",\"type\":\"invalid_request_error\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort() + "/v1";
        }

        String body() {
            return body.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
