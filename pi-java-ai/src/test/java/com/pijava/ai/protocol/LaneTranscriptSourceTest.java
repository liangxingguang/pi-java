package com.pijava.ai.protocol;

import java.time.Duration;
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
 * <b>包 A2 的接线夹具</b>（{@code docs/49 §7.1}）：系统提示与工具声明的**来源**必须是
 * transcript 的系统消息，而不是 {@code StreamRequest} 上那对已删掉的 legacy 字段。
 *
 * <p>观测面是**真出站请求体**（{@link RecordingHttpServer}）—— 与
 * {@code LaneTransformMessagesWiringTest} 同一理由：反射私有构建器会绑死形参表，
 * 而桩录的是请求字节，端到端（消息 → 归一 → 车道的系统槽位）都看得见。</p>
 *
 * <p>每条的三个断言成组，缺一不可：①提示文本进了**本车道的系统槽位**；
 * ②工具表来自系统消息的 {@code toolsAdded}（重放）；③系统消息**不再**作为会话项出现
 * （曾静默落成 assistant 文本 / 被吞掉 / 抛异常，见 {@code docs/49 §4.1}）。</p>
 */
class LaneTranscriptSourceTest {

    private static final String PROMPT = "be brief";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolDefinition TOOL =
        new ToolDefinition("lookup", "Look up a value", Map.of("type", "object"));

    // ── 五条车道的系统槽位 ─────────────────────────────────────────────

    /**
     * pi {@code anthropic-messages.ts:1043-1044}：文本进顶层 {@code system}，消息被切出数组。
     *
     * <p>⚠️ 本车道的 {@code system} 是**字符串**形态，pi 是 {@code [{type:"text",text:…}]}
     * 块数组（实测：{@code docs/49} 的 PR-3 探针）。这是**既有**线格偏差，包 A2 不动它
     * —— 新登记 F7（A-01 {@code cache_control} 会需要块形态）。</p>
     */
    @Test
    void anthropicLaneTakesSystemTextAndToolsFromTheTranscript() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(options(server), "ANTHROPIC_API_KEY");
            drain(api, transcriptRequest(ModelId.of("anthropic", "claude-sonnet-5"),
                List.of(prompt(), user("hi"))));

            var body = MAPPER.readTree(server.body());
            assertThat(body.path("system").asText()).isEqualTo(PROMPT);
            assertThat(body.path("tools")).hasSize(1);
            assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("lookup");
            assertThat(roles(body.path("messages"))).containsExactly("user");
        }
    }

    /** pi {@code openai-completions.ts:1249-1252}：下标 0 的系统消息落成 instruction 消息。 */
    @Test
    void completionsLaneTakesSystemTextAndToolsFromTheTranscript() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(options(server), "OPENAI_API_KEY");
            drain(api, transcriptRequest(ModelId.of("openai", "gpt-4o"),
                List.of(prompt(), user("hi"))));

            var body = MAPPER.readTree(server.body());
            assertThat(roles(body.path("messages"))).containsExactly("system", "user");
            assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo(PROMPT);
            assertThat(body.path("tools")).hasSize(1);
            assertThat(body.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("lookup");
        }
    }

    /** pi {@code google-generative-ai.ts:390-393}：文本进 {@code systemInstruction}，contents 无系统项。 */
    @Test
    void googleLaneTakesSystemTextAndToolsFromTheTranscript() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(options(server));
            drain(api, transcriptRequest(ModelId.of("google", "gemini-2.5-pro"),
                List.of(prompt(), user("hi"))));

            var body = MAPPER.readTree(server.body());
            assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo(PROMPT);
            assertThat(body.path("tools").get(0).path("functionDeclarations").get(0)
                .path("name").asText()).isEqualTo("lookup");
            assertThat(roles(body.path("contents"))).containsExactly("user");
        }
    }

    /** pi {@code mistral-conversations.ts:787-789}：系统消息**就地**落成 role=system 的条目。 */
    @Test
    void mistralLaneTakesSystemTextAndToolsFromTheTranscript() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new MistralConversationsApi(options(server));
            drain(api, transcriptRequest(ModelId.of("mistral", "mistral-large-latest"),
                List.of(prompt(), user("hi"))));

            var body = MAPPER.readTree(server.body());
            assertThat(roles(body.path("messages"))).containsExactly("system", "user");
            assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo(PROMPT);
            assertThat(body.path("tools")).hasSize(1);
            assertThat(body.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("lookup");
        }
    }

    /**
     * pi {@code openai-responses-shared.ts:218-222}：文本落成 input 里的 system 项。
     *
     * <p>⚠️ 本夹具**不带工具**：Responses 车道带工具时**在 A2 之前就**抛
     * {@code `strict` is required, but was not set}（OpenAI SDK 的 {@code FunctionTool}
     * 要求 {@code strict}，本仓从不设置它）—— 实测方式：把 A2b 的改动 stash 掉、旧代码复跑，
     * 报错一模一样 ⇒ **既有硬故障**，新登记 F8（不属包 A2）。工具那一半等 F8 修好再补。</p>
     */
    @Test
    void responsesLaneTakesSystemTextFromTheTranscript() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(options(server), "OPENAI_API_KEY");
            drain(api, transcriptRequest(ModelId.of("openai", "gpt-5"),
                List.of(textOnlyPrompt(PROMPT), user("hi"))));

            var body = MAPPER.readTree(server.body());
            assertThat(roles(body.path("input"))).containsExactly("system", "user");
            assertThat(body.path("input").get(0).path("content").asText()).isEqualTo(PROMPT);
        }
    }

    // ── 中途系统消息：折叠 ────────────────────────────────────────────

    /**
     * pi 的 {@code resolveTranscript(context, undefined)} 走**折叠**支
     * （{@code transcript.ts:113-120}）—— 中途系统消息的文本并入头、消息本身消失。
     *
     * <p>期望值取自 pi 的实测探针 PR-4（{@code docs/49 §7.6}）：{@code system} 为
     * {@code "head\n\nmid"}，会话数组只剩两条 user。</p>
     */
    @Test
    void anthropicLaneCollapsesAMidListSystemMessageIntoTheHead() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(options(server), "ANTHROPIC_API_KEY");
            drain(api, transcriptRequest(ModelId.of("anthropic", "claude-sonnet-5"),
                List.of(prompt("head"), user("a"), lateSystem("mid"), user("b"))));

            var body = MAPPER.readTree(server.body());
            assertThat(body.path("system").asText()).isEqualTo("head\n\nmid");
            assertThat(roles(body.path("messages"))).containsExactly("user", "user");
        }
    }

    /** Google **无条件**折叠（pi {@code google-generative-ai.ts:65}，不看 compat）。 */
    @Test
    void googleLaneCollapsesAMidListSystemMessageIntoTheSystemInstruction() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(options(server));
            drain(api, transcriptRequest(ModelId.of("google", "gemini-2.5-pro"),
                List.of(prompt("head"), user("a"), lateSystem("mid"), user("b"))));

            var body = MAPPER.readTree(server.body());
            assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo("head\n\nmid");
            // contents 只剩两条 user（无 model、无 system）—— 中途系统消息不落成内容项
            assertThat(roles(body.path("contents"))).containsExactly("user", "user");
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    /**
     * 车道面请求：**不带** legacy 字段，系统提示与工具都在 transcript 的消息里。
     *
     * <p>走 legacy 构造器（{@code systemPrompt = null}、{@code tools} 空）是**故意**的：
     * 归一层的重复守卫（{@code ContextNormalizer.java:30-34}）会因消息已以系统消息开头而
     * 原样放行，于是本夹具同时钉住「消息里的系统消息到达了车道」。</p>
     */
    private static StreamRequest transcriptRequest(ModelId<?> model, List<Message> messages) {
        return new StreamRequest(ModelInfo.minimal(model), null, messages, List.of(),
            -1, -1, Map.of());
    }

    /** 前导系统消息：文本 + 一条工具声明（{@code toolsAdded}，pi 的 {@code createInitialSystemMessage} 形状）。 */
    private static Message.SystemMessage prompt() {
        return prompt(PROMPT);
    }

    private static Message.SystemMessage prompt(String text) {
        return new Message.SystemMessage(text, java.time.Instant.EPOCH, Map.of(),
            List.of(TOOL), List.of());
    }

    /** 无工具声明的系统消息（Responses 车道今天带不了工具，见该测试的说明）。 */
    private static Message.SystemMessage textOnlyPrompt(String text) {
        return new Message.SystemMessage(text, java.time.Instant.EPOCH, Map.of(),
            List.of(), List.of());
    }

    /** 中途系统消息：无工具、无 section —— 折叠时只贡献文本。 */
    private static Message.SystemMessage lateSystem(String text) {
        return new Message.SystemMessage(text, java.time.Instant.EPOCH, Map.of(),
            List.of(), List.of());
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

    private static List<String> roles(JsonNode array) {
        var roles = new ArrayList<String>();
        for (var item : array) {
            roles.add(item.path("role").asText());
        }
        return roles;
    }
}
