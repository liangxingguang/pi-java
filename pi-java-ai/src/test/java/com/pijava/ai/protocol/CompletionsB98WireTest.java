package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.VercelGatewayRouting;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.builtin.ModelData;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B98（docs/51）：completions 车道七个 compat 字段的线格形状。
 * 观测面 {@link RecordingHttpServer} 抓真实请求体；断言按键取值（SDK 键序不钉）。
 *
 * <p>背景同 {@link ThinkingFormatWireTest}：baseUrl localhost/provider {@code test}
 * 探测给 openai 默认（usage-in-streaming=true 等）；显式 compat ≙ models.json。</p>
 */
class CompletionsB98WireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── U1 supportsUsageInStreaming（pi :834，!== false 门）─────────────

    @Test
    void explicitUsageFalseOmitsStreamOptions() throws Exception {
        var body = body(compat(b -> b.usage = false),
            msgs(user("hi")), List.of(), Optional.empty());
        assertThat(body.has("stream_options")).isFalse();
    }

    @Test
    void usageDefaultStillIncludesUsage() throws Exception {
        // 对照：探测默认 true ⇒ 恒有 include_usage（消费者现状也满足，守卫）。
        var body = body(compat(b -> {}), msgs(user("hi")), List.of(), Optional.empty());
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    // ── U2 zaiToolStream（pi :856-860）────────────────────────────────

    @Test
    void builtinZaiModelSendsToolStreamWithRequestTools() throws Exception {
        var model = ModelData.zaiModels().find(ModelId.of("zai", "glm-4.7")).orElseThrow();
        var body = body(model, msgs(user("hi")),
            List.of(new ToolDefinition("write", "write file", Map.of("type", "object"))),
            Optional.empty());

        assertThat(body.path("tool_stream").asBoolean()).isTrue();
        assertThat(body.has("tools")).isTrue();
    }

    @Test
    void zaiToolStreamAbsentWithoutRequestTools() throws Exception {
        // pi 的门是 requestTools.length > 0；历史工具只给 tools:[]，不发 tool_stream。
        var model = ModelData.zaiModels().find(ModelId.of("zai", "glm-4.7")).orElseThrow();
        var body = body(model, msgs(user("hi")), List.of(), Optional.empty());
        assertThat(body.has("tool_stream")).isFalse();
    }

    // ── U3 vllmPriority（pi :873-875）─────────────────────────────────

    @Test
    void vllmPriorityBecomesTopLevelPriority() throws Exception {
        var body = body(compat(b -> b.vllmPriority = -1),
            msgs(user("hi")), List.of(), Optional.empty());
        assertThat(body.path("priority").asInt()).isEqualTo(-1);
    }

    @Test
    void priorityAbsentByDefault() throws Exception {
        var body = body(compat(b -> {}), msgs(user("hi")), List.of(), Optional.empty());
        assertThat(body.has("priority")).isFalse();
    }

    // ── U4 vercelGatewayRouting（pi :992-1001）────────────────────────

    @Test
    void vercelOnlyRoutingBecomesProviderOptions() throws Exception {
        var body = body(compat(b -> b.vercel = new VercelGatewayRouting(
                List.of("bedrock", "anthropic"), null)),
            msgs(user("hi")), List.of(), Optional.empty());

        assertThat(body.path("providerOptions").has("gateway")).isTrue();
        var gateway = body.path("providerOptions").path("gateway");
        assertThat(gateway.path("only").get(0).asText()).isEqualTo("bedrock");
        assertThat(gateway.path("only").get(1).asText()).isEqualTo("anthropic");
        assertThat(gateway.has("order")).isFalse();
    }

    @Test
    void vercelOrderRoutingBecomesProviderOptions() throws Exception {
        var body = body(compat(b -> b.vercel = new VercelGatewayRouting(
                null, List.of("anthropic", "openai"))),
            msgs(user("hi")), List.of(), Optional.empty());

        assertThat(body.path("providerOptions").has("gateway")).isTrue();
        var gateway = body.path("providerOptions").path("gateway");
        assertThat(gateway.path("order").get(0).asText()).isEqualTo("anthropic");
        assertThat(gateway.has("only")).isFalse();
    }

    @Test
    void emptyVercelRoutingOmitsProviderOptions() throws Exception {
        var body = body(compat(b -> b.vercel = new VercelGatewayRouting(null, null)),
            msgs(user("hi")), List.of(), Optional.empty());
        assertThat(body.has("providerOptions")).isFalse();
    }

    // ── U5 requiresToolResultName（pi :1429-1432）─────────────────────

    @Test
    void toolResultCarriesNameWhenRequired() throws Exception {
        List<Message> messages = List.of(
            assistantToolCall(),
            new Message.ToolResultMessage("call_1", "write",
                List.of(new ContentBlock.TextContent("done")), false),
            user("next"));
        var body = body(compat(b -> b.toolResultName = true), messages, List.of(),
            Optional.empty());

        var tool = toolMessage(body);
        assertThat(tool.path("name").asText()).isEqualTo("write");
    }

    @Test
    void toolResultOmitsNameByDefault() throws Exception {
        List<Message> messages = List.of(
            assistantToolCall(),
            new Message.ToolResultMessage("call_1", "write",
                List.of(new ContentBlock.TextContent("done")), false),
            user("next"));
        var body = body(compat(b -> {}), messages, List.of(), Optional.empty());
        assertThat(toolMessage(body).has("name")).isFalse();
    }

    // ── U6 requiresAssistantAfterToolResult（pi :1241/:1295/:1451）────

    @Test
    void bridgeAssistantInsertedBetweenToolResultAndUser() throws Exception {
        List<Message> messages = List.of(
            assistantToolCall(),
            new Message.ToolResultMessage("call_1", "write",
                List.of(new ContentBlock.TextContent("done")), false),
            user("next"));
        var body = body(compat(b -> b.assistantAfterToolResult = true),
            messages, List.of(), Optional.empty());

        var roles = roles(body);
        assertThat(roles).containsSubsequence("assistant", "tool", "assistant", "user");
        int toolIndex = roles.indexOf("tool");
        int bridgeIndex = roles.subList(toolIndex + 1, roles.size()).indexOf("assistant")
            + toolIndex + 1;
        var bridge = body.path("messages").get(bridgeIndex);
        assertThat(bridge.path("content").asText())
            .isEqualTo("I have processed the tool results.");
    }

    @Test
    void noBridgeWithoutTheFlag() throws Exception {
        List<Message> messages = List.of(
            assistantToolCall(),
            new Message.ToolResultMessage("call_1", "write",
                List.of(new ContentBlock.TextContent("done")), false),
            user("next"));
        var body = body(compat(b -> {}), messages, List.of(), Optional.empty());
        // 前导 "developer" 是 THINKING 模型在默认 compat 下的指令角色（探测给真）。
        assertThat(roles(body)).containsExactly("developer", "assistant", "tool", "user");
    }

    @Test
    void assistantWithOnlyToolCallsCarriesEmptyStringContent() throws Exception {
        // pi :1294-1296：flag 开时 assistant 消息默认 content 是 ""（不是省略）。
        var messages = msgs(assistantToolCall());
        var body = body(compat(b -> b.assistantAfterToolResult = true),
            messages,
            List.of(new ToolDefinition("write", "write file", Map.of("type", "object"))),
            Optional.empty());

        var assistant = assistantMessage(body);
        assertThat(assistant.has("content")).isTrue();
        assertThat(assistant.path("content").asText()).isEqualTo("");
        assertThat(assistant.has("tool_calls")).isTrue();
    }

    @Test
    void bridgeInsertedBeforeSyntheticToolImageUser() throws Exception {
        var imageResult = new Message.ToolResultMessage("call_1", "read",
            List.of(new ContentBlock.ImageContent("image/png", "AAAA")), false);
        var body = imageBody(compat(b -> b.assistantAfterToolResult = true),
            msgs(assistantToolCall(), imageResult));
        assertThat(roles(body)).containsSubsequence("tool", "assistant", "user");
    }

    // ── U7 requiresThinkingAsText（pi :1322-1330）─────────────────────

    @Test
    void thinkingBlocksReplayAsTextPartsWithoutReasoningFields() throws Exception {
        var assistant = new Message.AssistantMessage(List.of(
            new ContentBlock.ThinkingContent("internal reasoning", null, false),
            new ContentBlock.TextContent("visible answer")), "stop", null,
            "openai-completions", "test", "reasoner", null, null, null, null);
        var body = body(compat(b -> b.thinkingAsText = true),
            msgs(assistant, user("next")), List.of(), Optional.empty());

        var wire = assistantMessage(body);
        assertThat(wire.get("content").isArray()).isTrue();
        assertThat(wire.path("content").get(0).path("text").asText())
            .isEqualTo("internal reasoning");
        assertThat(wire.path("content").get(1).path("text").asText())
            .isEqualTo("visible answer");
        assertThat(wire.has("reasoning_content")).isFalse();
        assertThat(wire.has("reasoning")).isFalse();
    }

    @Test
    void thinkingOnlyReplayIsASingleTextPart() throws Exception {
        var assistant = new Message.AssistantMessage(List.of(
            new ContentBlock.ThinkingContent("internal reasoning", null, false)),
            "stop", null, "openai-completions", "test", "reasoner",
            null, null, null, null);
        var body = body(compat(b -> b.thinkingAsText = true),
            msgs(assistant), List.of(), Optional.empty());

        var wire = assistantMessage(body);
        assertThat(wire.path("content").get(0).path("text").asText())
            .isEqualTo("internal reasoning");
        assertThat(wire.has("reasoning_content")).isFalse();
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    /** B98 八开关（fallback 是 anthropic 专属，本车道恒 null）。 */
    private static final class B98 {
        Boolean toolResultName;
        Boolean assistantAfterToolResult;
        Boolean thinkingAsText;
        Boolean usage = Boolean.TRUE;
        Boolean zaiToolStream;
        Integer vllmPriority;
        VercelGatewayRouting vercel;
    }

    private static ModelCompat compat(java.util.function.Consumer<B98> set) {
        var b = new B98();
        set.accept(b);
        return new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, null, null, null, null, null,
            null, Map.of(), Map.of(), null, null, null, null, null, false, null,
            null, null,
            b.toolResultName, b.assistantAfterToolResult, b.thinkingAsText, b.usage,
            b.zaiToolStream, b.vllmPriority, b.vercel, null);
    }

    private static ModelInfo model(ModelCompat compat, Set<ModelCapability> capabilities) {
        return new ModelInfo(ModelId.of("test", "reasoner"), "reasoner",
            capabilities, 200_000, 100_000, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    private static JsonNode body(ModelCompat compat, List<Message> messages,
                                 List<ToolDefinition> tools,
                                 Optional<ThinkingLevel> reasoning) throws Exception {
        return body(model(compat,
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING)), messages, tools, reasoning);
    }

    private static JsonNode imageBody(ModelCompat compat, List<Message> messages)
            throws Exception {
        return body(model(compat,
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING, ModelCapability.IMAGE_INPUT)),
            messages, List.of(), Optional.empty());
    }

    private static JsonNode body(ModelInfo model, List<Message> messages,
                                 List<ToolDefinition> tools,
                                 Optional<ThinkingLevel> reasoning) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model, "be brief", messages, tools,
                -1, -1, reasoning);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到。
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static List<Message> msgs(Message... messages) {
        return List.of(messages);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.AssistantMessage assistantToolCall() {
        return new Message.AssistantMessage(List.of(
            new ContentBlock.ToolUseContent("call_1", "write",
                Map.of("path", "a"))), "toolUse", null,
            "openai-completions", "test", "reasoner", null, null, null, null);
    }

    private static JsonNode toolMessage(JsonNode body) {
        for (var m : body.path("messages")) {
            if ("tool".equals(m.path("role").asText())) {
                return m;
            }
        }
        throw new AssertionError("no tool message in request");
    }

    private static JsonNode assistantMessage(JsonNode body) {
        for (var m : body.path("messages")) {
            if ("assistant".equals(m.path("role").asText())) {
                return m;
            }
        }
        throw new AssertionError("no assistant message in request");
    }

    private static java.util.List<String> roles(JsonNode body) {
        var roles = new java.util.ArrayList<String>();
        body.path("messages").forEach(m -> roles.add(m.path("role").asText()));
        return roles;
    }
}
