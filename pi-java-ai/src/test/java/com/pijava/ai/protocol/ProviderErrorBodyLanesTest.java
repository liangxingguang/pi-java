package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageRequest;
import com.pijava.ai.api.ImageStopReason;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.ContextOverflow;
import com.pijava.ai.utils.RetryableError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B135 + B141：provider 错误体透传的<b>车道级</b>真 HTTP 夹具（设计 原 docs/48 §6.2）。
 *
 * <p>全部走 {@link RecordingHttpServer} 真桩驱动真实 SDK / JDK HTTP 栈，断言终局
 * {@code AssistantMessage.errorMessage} 与 pi 逐字一致：</p>
 * <ul>
 *   <li>L1–L6 completions：字符串 error 节点、bodyless 400/413 文案、Cerebras 溢出
 *       门救活、OpenRouter metadata.raw 追加规则；</li>
 *   <li>L7–L11 responses / azure：{@code <Provider> API error (N): …} 前缀、
 *       ChatGPT 用量链接、内部 throw 不套前缀；</li>
 *   <li>L12 images；L13–L15 Mistral 裸 fetch 自有格式。</li>
 * </ul>
 */
class ProviderErrorBodyLanesTest {

    // ══════════════════════════════════════════════════════════════════
    // completions 车道
    // ══════════════════════════════════════════════════════════════════

    /** L1：嵌套 error 是<b>字符串</b>（非对象）⇒ pi makeMessage 给 {@code N "text"}。 */
    @Test
    void completionsStringErrorNodeIsJsonQuoted() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403, "{\"error\":\"blocked by gateway WAF\"}");

            var msg = settleCompletions(server, "openrouter");

            assertThat(msg.errorMessage()).isEqualTo("403 \"blocked by gateway WAF\"");
            // 403 普通网关文本不进瞬断白名单。
            assertThat(RetryableError.isRetryableAssistantError(msg)).isFalse();
        }
    }

    /** L2：400 空体 ⇒ 逐字 {@code 400 status code (no body)}（B141，不是 SDK 的 400: Unknown）。 */
    @Test
    void completionsBodyless400UsesPiLiteral() throws Exception {
        try (var server = new RecordingHttpServer()) {
            emptyError(server, 400);

            var msg = settleCompletions(server, "openrouter");

            assertThat(msg.errorMessage()).isEqualTo("400 status code (no body)");
        }
    }

    /** L3：413 空体 + provider=cerebras ⇒ 命中 bodyless 溢出门（自动压缩恢复由此触发）。 */
    @Test
    void cerebrasBodyless413IsContextOverflow() throws Exception {
        try (var server = new RecordingHttpServer()) {
            emptyError(server, 413);

            var msg = settleCompletions(server, "cerebras");

            assertThat(msg.errorMessage()).isEqualTo("413 status code (no body)");
            assertThat(ContextOverflow.isContextOverflow(msg, null)).isTrue();
        }
    }

    /** L4：同样的空体文案，门控 provider 不是 cerebras ⇒ 不算溢出（门仍只对 cerebras 开）。 */
    @Test
    void bodyless400ForOtherProviderIsNotContextOverflow() throws Exception {
        try (var server = new RecordingHttpServer()) {
            emptyError(server, 400);

            var msg = settleCompletions(server, "openrouter");

            assertThat(ContextOverflow.isContextOverflow(msg, null)).isFalse();
        }
    }

    /** L5：OpenRouter {@code metadata.raw} 已在 body JSON 里 ⇒ 不重复追加（raw 恰出现 1 次）。 */
    @Test
    void openRouterMetadataRawAlreadyInBodyAppearsExactlyOnce() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403, "{\"error\":{\"message\":\"Provider returned error\","
                + "\"code\":403,\"metadata\":{\"raw\":\"upstream WAF blocked policy XYZ\"}}}");

            var msg = settleCompletions(server, "openrouter");
            String text = msg.errorMessage();

            assertThat(countOccurrences(text, "upstream WAF blocked policy XYZ")).isEqualTo(1);
            assertThat(text).startsWith("403: ");
            assertThat(text).contains("Provider returned error");
        }
    }

    /**
     * L6：{@code metadata.raw} 含换行（在 body JSON 里被转义）⇒ 检测到「未含」后
     * <b>另起一行</b>追加 raw 原文。
     */
    @Test
    void openRouterMetadataRawWithNewlineIsAppendedOnANewline() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403, "{\"error\":{\"message\":\"upstream blocked\","
                + "\"metadata\":{\"raw\":\"line1\\nline2\"}}}");

            var msg = settleCompletions(server, "openrouter");
            String text = msg.errorMessage();

            // body JSON 里是转义形态（\n 两字符），追加的是真实换行形态。
            assertThat(text).contains("\"raw\":\"line1\\nline2\"");
            assertThat(text).endsWith("\nline1\nline2");
            assertThat(countOccurrences(text, "line1")).isEqualTo(2);
        }
    }

    /** 429 错误体含 "rate limit" ⇒ 进瞬断白名单（pi 看到的就是透出后的同段文本）。 */
    @Test
    void rateLimitInBodyStaysRetryable() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 429,
                "{\"error\":{\"message\":\"rate limit hit\",\"type\":\"rate_limit_error\"}}");

            var msg = settleCompletions(server, "openrouter");

            assertThat(RetryableError.isRetryableAssistantError(msg)).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // responses 车道
    // ══════════════════════════════════════════════════════════════════

    /** L7：provider=openai ⇒ 前缀 {@code OpenAI API error (N): <body>}。 */
    @Test
    void responsesOpenaiGetsProviderPrefixAndBody() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403,
                "{\"error\":{\"message\":\"forbidden\",\"type\":\"forbidden\"}}");

            var msg = settleResponses(server, "openai");

            assertThat(msg.errorMessage())
                .isEqualTo("OpenAI API error (403): "
                    + "{\"message\":\"forbidden\",\"type\":\"forbidden\"}");
        }
    }

    /** L8：其他 provider 原样小写（{@code model.provider}，不做大小写美化）。 */
    @Test
    void responsesOtherProviderKeepsLowercasePrefix() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403,
                "{\"error\":{\"message\":\"forbidden\",\"type\":\"forbidden\"}}");

            var msg = settleResponses(server, "openrouter");

            assertThat(msg.errorMessage())
                .isEqualTo("openrouter API error (403): "
                    + "{\"message\":\"forbidden\",\"type\":\"forbidden\"}");
        }
    }

    /** L9：错误体含订阅共享用量码 ⇒ 追加 ChatGPT 用量链接。 */
    @Test
    void responsesSubscriptionSharingLimitAppendsUsageUrl() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403, "{\"error\":{\"message\":"
                + "\"subscription_sharing_usage_limit_exceeded\",\"type\":\"invalid_request_error\"}}");

            var msg = settleResponses(server, "openai");

            assertThat(msg.errorMessage())
                .contains("OpenAI API error (403):")
                .endsWith("\nCheck your ChatGPT usage: https://chatgpt.com/settings/usage");
        }
    }

    /** L10：200 SSE 回 {@code response.failed} 帧 ⇒ 内部 throw 无 HTTP status，<b>不套前缀</b>。 */
    @Test
    void responsesFailedFrameIsNotPrefixed() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(200,
                Map.of("Content-Type", "text/event-stream"), failedSse().getBytes(StandardCharsets.UTF_8)));

            var msg = settleResponses(server, "openai");

            assertThat(msg.errorMessage()).isEqualTo("server_error: boom");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // azure 车道
    // ══════════════════════════════════════════════════════════════════

    /** L11：azure 恒为常量前缀 {@code Azure OpenAI API error}。 */
    @Test
    void azureGetsConstantPrefix() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403,
                "{\"error\":{\"message\":\"forbidden\",\"type\":\"forbidden\"}}");

            var options = new ApiOptions(server.baseUrl(), "test-key",
                Duration.ofSeconds(5), 0, Map.of("azureBaseUrl", server.baseUrl()));
            var api = new AzureOpenAIResponsesApi(options, "AZURE_OPENAI_API_KEY");
            var result = api.send(request("azure-openai-responses"), options);

            assertThat(result).isInstanceOf(Message.AssistantMessage.class);
            var msg = (Message.AssistantMessage) result;
            assertThat(msg.stopReason()).isEqualTo(StopReason.ERROR);
            assertThat(msg.errorMessage())
                .isEqualTo("Azure OpenAI API error (403): "
                    + "{\"message\":\"forbidden\",\"type\":\"forbidden\"}");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // images 车道（非流式）
    // ══════════════════════════════════════════════════════════════════

    /** L12：images 错误同样归一（带体给 body；空体给 no-body 文案），无 metadata 追加。 */
    @Test
    void imagesErrorsAreNormalized() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403, "{\"error\":{\"message\":\"forbidden\"}}");

            var result = generateImage(server);

            assertThat(result.stopReason()).isEqualTo(ImageStopReason.ERROR);
            assertThat(result.errorMessage())
                .isEqualTo("403: {\"message\":\"forbidden\"}");
        }
        try (var server = new RecordingHttpServer()) {
            emptyError(server, 400);

            var result = generateImage(server);

            assertThat(result.stopReason()).isEqualTo(ImageStopReason.ERROR);
            assertThat(result.errorMessage()).isEqualTo("400 status code (no body)");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Mistral 裸 fetch 车道
    // ══════════════════════════════════════════════════════════════════

    /** L13：403 带体 ⇒ {@code Mistral API error (N): <原始 body>}（raw 文本，不抽 message）。 */
    @Test
    void mistralErrorWithBodyUsesRawBody() throws Exception {
        try (var server = new RecordingHttpServer()) {
            jsonError(server, 403, "{\"error\":{\"message\":\"upstream denied\"}}");

            var msg = settleMistral(server);

            assertThat(msg.errorMessage())
                .isEqualTo("Mistral API error (403): "
                    + "{\"error\":{\"message\":\"upstream denied\"}}");
        }
    }

    /** L14：400 空体 ⇒ pi 兜底字面量（不是异常 message 里的 "HTTP 400"）。 */
    @Test
    void mistralBodylessUsesPiFallbackLiteral() throws Exception {
        try (var server = new RecordingHttpServer()) {
            emptyError(server, 400);

            var msg = settleMistral(server);

            assertThat(msg.errorMessage())
                .isEqualTo("Mistral API error (400): Request failed with status 400");
        }
    }

    /** L15：4500 字符体 ⇒ 截到 4000，pi 截断后缀；文案里没有 "HTTP 4"。 */
    @Test
    void mistralLongBodyIsTruncatedWithPiSuffix() throws Exception {
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(400,
                Map.of("Content-Type", "text/plain"),
                ("x".repeat(4500)).getBytes(StandardCharsets.UTF_8)));

            var msg = settleMistral(server);
            String text = msg.errorMessage();

            assertThat(text).startsWith("Mistral API error (400): " + "x".repeat(4000));
            assertThat(text).endsWith("... [truncated 500 chars]");
            assertThat(text).doesNotContain("HTTP 4");
        }
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private static ApiOptions options(String baseUrl) {
        return new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, Map.of());
    }

    private static StreamRequest request(String provider) {
        ModelInfo model = ModelInfo.minimal(ModelId.of(provider, "test-model"));
        Message user = new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi")));
        return new StreamRequest(model, "", List.of(user), List.of(), -1, -1);
    }

    private static void jsonError(RecordingHttpServer server, int status, String json) {
        server.enqueue(new RecordingHttpServer.Response(status,
            Map.of("Content-Type", "application/json"),
            json.getBytes(StandardCharsets.UTF_8)));
    }

    private static void emptyError(RecordingHttpServer server, int status) {
        server.enqueue(new RecordingHttpServer.Response(status,
            Map.of("Content-Type", "application/json"), new byte[0]));
    }

    private static Message.AssistantMessage settleCompletions(RecordingHttpServer server,
                                                              String provider) {
        var options = options(server.baseUrl());
        var api = new OpenAICompletionsApi(options, "OPENAI_API_KEY");
        return settleError(api.send(request(provider), options));
    }

    private static Message.AssistantMessage settleResponses(RecordingHttpServer server,
                                                             String provider) {
        var options = options(server.baseUrl());
        var api = new OpenAIResponsesApi(options, "OPENAI_API_KEY");
        return settleError(api.send(request(provider), options));
    }

    private static Message.AssistantMessage settleMistral(RecordingHttpServer server) {
        var options = options(server.baseUrl());
        var api = new MistralConversationsApi(options);
        return settleError(api.send(request("mistral"), options));
    }

    private static com.pijava.ai.api.ImageResult generateImage(RecordingHttpServer server) {
        var options = options(server.baseUrl());
        var api = new OpenRouterImagesApi(options, "OPENROUTER_API_KEY");
        return api.generate(new ImageRequest(
            ModelId.of("openrouter", "test-image-model"),
            List.of(new ContentBlock.TextContent("draw"))), options);
    }

    private static Message.AssistantMessage settleError(Message result) {
        assertThat(result).isInstanceOf(Message.AssistantMessage.class);
        var msg = (Message.AssistantMessage) result;
        assertThat(msg.stopReason()).isEqualTo(StopReason.ERROR);
        return msg;
    }

    /** {@code response.failed} SSE 帧（与 {@code OpenAIResponsesApiTest.failedSse()} 同形）。 */
    private static String failedSse() {
        return "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"in_progress\",\"model\":\"gpt-4o\",\"output\":[],"
            + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null}}\n\n"
            + "data: {\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"failed\",\"model\":\"gpt-4o\",\"output\":[],"
            + "\"parallel_tool_calls\":true,\"tools\":[],\"usage\":null,"
            + "\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}\n\n"
            + "data: [DONE]\n\n";
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
