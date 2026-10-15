package com.pijava.ai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * B81：OAuth（Claude Code 伪装）车道的**出站身份面**与**入站工具名 demap**，
 * 逐点对齐 pi {@code api/anthropic-messages.ts}：
 * 系统前缀（{@code :1168-1188}）、beta 对（{@code :1106}）、版本 2.1.280（{@code :96}）、
 * 工具名出站三处（{@code :1347/:1443/:1603}）、入站 demap（{@code :578/:728}）。
 *
 * <p>观测面是真出站字节与 SSE 事件（{@link RecordingHttpServer}）。</p>
 */
class AnthropicOAuthWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX =
        "You are Claude Code, Anthropic's official CLI for Claude.";
    private static final ModelId<?> ANTHROPIC_MODEL = ModelId.of("anthropic", "claude-sonnet-5");

    // ── 出站：系统提示 ────────────────────────────────────────────────

    @Test
    void oauthPrependsClaudeCodeSystemBlock() throws Exception {
        var captured = capture(AuthKind.OAUTH, "oat-tok",
            ModelInfo.minimal(ANTHROPIC_MODEL), "be brief",
            List.of(user("hi")), List.of());

        var system = captured.body().path("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system.get(0).path("text").asText()).isEqualTo(PREFIX);
        assertThat(system.get(1).path("text").asText()).isEqualTo("be brief");
    }

    @Test
    void oauthSendsPrefixAloneWhenThereIsNoSystemPrompt() throws Exception {
        var captured = capture(AuthKind.OAUTH, "oat-tok",
            ModelInfo.minimal(ANTHROPIC_MODEL), null,
            List.of(user("hi")), List.of());

        var system = captured.body().path("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system).hasSize(1);
        assertThat(system.get(0).path("text").asText()).isEqualTo(PREFIX);
    }

    @Test
    void plainApiKeyKeepsSingleSystemBlockAndNoOauthBetas() throws Exception {
        var captured = capture(AuthKind.API_KEY, "sk-ant-api01-plain",
            ModelInfo.minimal(ANTHROPIC_MODEL), "be brief",
            List.of(user("hi")), List.of());

        var system = captured.body().path("system");
        assertThat(system).hasSize(1);
        assertThat(system.get(0).path("text").asText()).isEqualTo("be brief");
        assertThat(captured.headers()).doesNotContainKey("anthropic-beta");
    }

    // ── 出站：beta 对与版本头 ─────────────────────────────────────────

    @Test
    void oauthPutsIdentityBetasFirstAndBumpsDisguiseVersion() throws Exception {
        var captured = capture(AuthKind.OAUTH, "oat-tok",
            ModelInfo.minimal(ANTHROPIC_MODEL), null,
            List.of(user("hi")), List.of());

        assertThat(captured.headers().get("anthropic-beta"))
            .isEqualTo("claude-code-20250219,oauth-2025-04-20");
        assertThat(captured.headers().get("user-agent")).isEqualTo("claude-cli/2.1.280");
        assertThat(captured.headers().get("x-app")).isEqualTo("cli");
        assertThat(captured.headers().get("authorization")).isEqualTo("Bearer oat-tok");
    }

    // ── 出站：工具名（convertTools / tool_use / tool_removal） ────────

    @Test
    void oauthMapsTopLevelToolNames() throws Exception {
        var tools = List.of(tool("read"), tool("mcp__demo__thing"));

        var oauth = capture(AuthKind.OAUTH, "oat-tok",
            ModelInfo.minimal(ANTHROPIC_MODEL), null, List.of(user("hi")), tools);
        assertThat(toolNames(oauth.body())).containsExactly("Read", "mcp__demo__thing");

        var plain = capture(AuthKind.API_KEY, "sk-ant-api01-plain",
            ModelInfo.minimal(ANTHROPIC_MODEL), null, List.of(user("hi")), tools);
        assertThat(toolNames(plain.body())).containsExactly("read", "mcp__demo__thing");
    }

    @Test
    void oauthMapsHistoricAssistantToolUseName() throws Exception {
        var transcript = List.<Message>of(
            assistantToolCall("toolu_1", "read"),
            new Message.ToolResultMessage("toolu_1", "read",
                List.of(new ContentBlock.TextContent("done")), false),
            user("next"));

        var oauth = capture(AuthKind.OAUTH, "oat-tok",
            ModelInfo.minimal(ANTHROPIC_MODEL), null, transcript, List.of(tool("read")));
        assertThat(firstToolUseName(oauth.body())).isEqualTo("Read");

        var plain = capture(AuthKind.API_KEY, "sk-ant-api01-plain",
            ModelInfo.minimal(ANTHROPIC_MODEL), null, transcript, List.of(tool("read")));
        assertThat(firstToolUseName(plain.body())).isEqualTo("read");
    }

    @Test
    void oauthMapsNativeToolChangeNamesButNotThePlaceholder() throws Exception {
        var transcript = List.<Message>of(
            new Message.SystemMessage("base prompt", java.time.Instant.EPOCH, Map.of(),
                List.of(tool("read")), List.of()),
            user("before"),
            new Message.SystemMessage("", java.time.Instant.ofEpochMilli(2), Map.of(),
                List.of(tool("grep")), List.of(new ToolReference("read"))));

        var oauth = capture(AuthKind.OAUTH, "oat-tok", nativeModel(), null,
            transcript, List.of());
        // 顶层表：映射后的初始工具 ＋ 原样的占位符。
        assertThat(toolNames(oauth.body())).containsExactly("Read", "__pi_deferred_placeholder__");
        var blocks = lastContent(oauth.body());
        assertThat(blockTypes(blocks)).contains("tool_removal", "tool_addition");
        assertThat(blockByName(blocks, "tool_removal").path("tool").path("name").asText())
            .isEqualTo("Read");
        assertThat(blockByName(blocks, "tool_addition").path("tool").path("definition")
            .path("name").asText()).isEqualTo("Grep");

        var plain = capture(AuthKind.API_KEY, "sk-ant-api01-plain",
            nativeModel(), null, transcript, List.of());
        assertThat(toolNames(plain.body())).containsExactly("read", "__pi_deferred_placeholder__");
        var plainBlocks = lastContent(plain.body());
        assertThat(blockByName(plainBlocks, "tool_removal").path("tool").path("name").asText())
            .isEqualTo("read");
        assertThat(blockByName(plainBlocks, "tool_addition").path("tool").path("definition")
            .path("name").asText()).isEqualTo("grep");
    }

    // ── 入站：tool_use 名 demap（pi :725-730） ───────────────────────

    @Test
    void inboundToolUseNameIsDemappedAgainstCurrentTools() throws Exception {
        // 当前工具含小写 read ⇒ 模型回的 "Read" demap 回真名。
        var events = streamToolCallBlock(AuthKind.OAUTH, "oat-tok", "Read",
            List.of(tool("read")));
        assertThat(toolCallNames(events)).containsExactly("read");
    }

    @Test
    void inboundUnknownClaudeCodeNamePassesThrough() throws Exception {
        // 当前工具集无匹配（模型乱开 KillShell）⇒ 原样透传，交给分发层失败。
        var events = streamToolCallBlock(AuthKind.OAUTH, "oat-tok", "KillShell",
            List.of(tool("read")));
        assertThat(toolCallNames(events)).containsExactly("KillShell");
    }

    @Test
    void inboundNameIsNotDemappedForPlainApiKey() throws Exception {
        var events = streamToolCallBlock(AuthKind.API_KEY, "sk-ant-api01-plain", "Read",
            List.of(tool("read")));
        assertThat(toolCallNames(events)).containsExactly("Read");
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    private record Captured(JsonNode body, Map<String, String> headers) {
    }

    private static Captured capture(AuthKind kind, String key, ModelInfo model,
                                    String systemPrompt, List<Message> messages,
                                    List<ToolDefinition> tools) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), key, Duration.ofSeconds(5), 0,
                    Map.of(), kind),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, systemPrompt, messages, tools, -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 默认桩回 400：请求体/头已录到，流怎么结束与断言无关
            }
            return new Captured(MAPPER.readTree(server.body()), server.headers());
        }
    }

    private static List<StreamEvent> streamToolCallBlock(AuthKind kind, String key,
                                                         String wireName,
                                                         List<ToolDefinition> tools)
            throws Exception {
        var sse = sseBlock(wireName);
        try (var server = new RecordingHttpServer()) {
            server.enqueue(new RecordingHttpServer.Response(200,
                Map.of("Content-Type", "text/event-stream"),
                sse.getBytes(StandardCharsets.UTF_8)));
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), key, Duration.ofSeconds(5), 0,
                    Map.of(), kind),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(ModelInfo.minimal(ANTHROPIC_MODEL), null,
                List.of(user("hi")), tools, -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
                return List.copyOf(events);
            }
        }
    }

    private static String sseBlock(String toolName) {
        return sse("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-5\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}")
            + sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_9\","
                + "\"name\":\"" + toolName + "\",\"input\":{}}}")
            + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + sse("message_delta", "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":5}}")
            + sse("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static String sse(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    private static List<String> toolCallNames(List<StreamEvent> events) {
        var names = new ArrayList<String>();
        for (var event : events) {
            if (event instanceof StreamEvent.ToolCallStart start) {
                start.partial().content().stream()
                    .filter(ContentBlock.ToolUseContent.class::isInstance)
                    .map(ContentBlock.ToolUseContent.class::cast)
                    .findFirst()
                    .ifPresent(block -> names.add(block.name()));
            }
        }
        return names;
    }

    private static ModelInfo nativeModel() {
        // 两 flag 都给（supportsMidConvoSystemMessages/supportsMidConvoToolChanges）。
        var compat = new ModelCompat(false, null, true, false, true, null, true, null, null);
        return new ModelInfo(ModelId.of("anthropic", "claude-opus-5"), "Claude Opus 5",
            Set.of(), 100_000, 1000, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);
    }

    private static List<String> toolNames(JsonNode body) {
        var out = new ArrayList<String>();
        for (var tool : body.path("tools")) {
            out.add(tool.path("name").asText());
        }
        return out;
    }

    private static String firstToolUseName(JsonNode body) {
        for (var message : body.path("messages")) {
            for (var block : message.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    return block.path("name").asText();
                }
            }
        }
        throw new AssertionError("no tool_use block in request body");
    }

    private static JsonNode lastContent(JsonNode body) {
        var messages = body.path("messages");
        return messages.get(messages.size() - 1).path("content");
    }

    private static List<String> blockTypes(JsonNode content) {
        var out = new ArrayList<String>();
        content.forEach(block -> out.add(block.path("type").asText()));
        return out;
    }

    private static JsonNode blockByName(JsonNode content, String type) {
        for (var block : content) {
            if (type.equals(block.path("type").asText())) {
                return block;
            }
        }
        throw new AssertionError("missing block " + type);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.AssistantMessage assistantToolCall(String id, String name) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent(id, name, Map.of())),
            StopReason.TOOL_USE, null, "anthropic-messages", "anthropic", "claude-sonnet-5",
            null, null, null, null);
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, name + " tool", Map.of("type", "object"));
    }
}
