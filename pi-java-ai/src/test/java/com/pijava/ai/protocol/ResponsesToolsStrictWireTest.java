package com.pijava.ai.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>包 B87/B88</b>（{@code docs/50 §6.1}）：Responses 车道的**工具声明**必须发得出请求，
 * 且 {@code strict} 的有无按各车道在 pi 里的 compat 缺省走。
 *
 * <p>背景（B88 是既有硬故障）：{@code ResponsesMessageConverter} 从不设 {@code strict}，
 * 而 OpenAI SDK 的 {@code FunctionTool.Builder.build()} 把它标成必填
 * （{@code checkRequired("strict", strict)}，`Check.kt:11-12` 的消息逐字为
 * `` `strict` is required, but was not set ``）⇒ 抛在**构建期**，桩服务器**零请求**。
 * 本夹具因此把「请求发出去了」也当成断言的一部分 —— 只断言 body 形状的话，零请求时
 * 会以解析空 body 的形式失败，看起来像夹具写错了。</p>
 *
 * <p>观测面是**真出站请求体**（{@link RecordingHttpServer}），与
 * {@code LaneTranscriptSourceTest} 同一装置。期望值取自 pi 的两条车道与它自己的
 * oracle：openai-responses 的 compat 缺省 {@code supportsStrictMode ?? false}
 * （{@code openai-responses.ts:74}）⇒ 走 {@code openai-responses-shared.ts:391-393}
 * 的「不支持 ⇒ 整个键不发」；azure 的缺省相反（{@code azure-openai-responses.ts:296}/
 * {@code :319} 的 {@code ?? true}）⇒ 明确发 {@code strict:false}。
 * pi 侧的逐字期望：{@code constrained-sampling.test.ts:118-122}（不发的半条）与
 * {@code azure-openai-base-url.test.ts:190-209}（显式 compat 的对照）。</p>
 */
class ResponsesToolsStrictWireTest {

    private static final String PROMPT = "be brief";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolDefinition TOOL =
        new ToolDefinition("lookup", "Look up a value", Map.of("type", "object"));

    /**
     * pi {@code openai-responses.ts:74}（{@code ?? false}）＋
     * {@code openai-responses-shared.ts:391-393}：不支持 ⇒ **线上没有 {@code strict} 键**。
     */
    @Test
    void openAiResponsesLaneSendsToolsWithoutStrict() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(options(server), "OPENAI_API_KEY");
            drain(api, transcriptRequest(ModelId.of("openai", "gpt-5"),
                List.of(prompt(), user("hi"))));

            var body = MAPPER.readTree(server.body());
            var tool = body.path("tools").get(0);
            assertThat(server.body()).as("B88：请求必须真的发出去").isNotEmpty();
            assertThat(fieldNames(tool)).as("pi 的 ai 层 Tool 只有这四个键（types.ts:600-605）")
                .containsExactlyInAnyOrder("type", "name", "description", "parameters");
            assertThat(tool.path("strict").isMissingNode())
                .as("pi 的 compat 缺省是 false ⇒ strict 键整个不发").isTrue();
            assertThat(tool.path("name").asText()).isEqualTo("lookup");
            assertThat(tool.path("parameters").path("type").asText()).isEqualTo("object");
        }
    }

    /**
     * pi {@code azure-openai-responses.ts:296}/{@code :319}（{@code ?? true}）：
     * **同一个工具**在 azure 车道要发 {@code strict:false}（pi 自己的对照测试
     * {@code azure-openai-base-url.test.ts:190-209} 断言的是「显式 false 时不带」，
     * 反向即本条）。
     */
    @Test
    void azureResponsesLaneSendsExplicitStrictFalse() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AzureOpenAIResponsesApi(options(server), "AZURE_OPENAI_API_KEY");
            drain(api, transcriptRequest(ModelId.of("azure-openai-responses", "gpt-4o"),
                List.of(prompt(), user("hi"))));

            var body = MAPPER.readTree(server.body());
            var tool = body.path("tools").get(0);
            assertThat(server.body()).as("B88：请求必须真的发出去").isNotEmpty();
            assertThat(tool.path("strict").isBoolean()).as("azure 侧 strict 在场").isTrue();
            assertThat(tool.path("strict").asBoolean())
                .as("java 没有 constrainedSampling ⇒ 恒为 false（docs/50 §3 F6）").isFalse();
            assertThat(tool.path("name").asText()).isEqualTo("lookup");
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static StreamRequest transcriptRequest(ModelId<?> model, List<Message> messages) {
        return new StreamRequest(ModelInfo.minimal(model), null, messages, List.of(),
            -1, -1, Map.of());
    }

    /** 前导系统消息：文本 + 一条工具声明（重放后 `getCurrentTools` 得到它）。 */
    private static Message.SystemMessage prompt() {
        return new Message.SystemMessage(PROMPT, Instant.EPOCH, Map.of(),
            List.of(TOOL), List.of());
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ApiOptions options(RecordingHttpServer server) {
        return new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of());
    }

    /** 跑一条流到收场（桩回 400，请求体已录到）。 */
    private static void drain(AbstractChatApi api, StreamRequest request) {
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            var events = new ArrayList<StreamEvent>();
            while (iter.hasNext() && events.size() < 100) {
                events.add(iter.next());
            }
        } catch (Exception ignored) {
            // 桩回 400：请求体已录到，流怎么结束与断言无关
        }
    }
}
