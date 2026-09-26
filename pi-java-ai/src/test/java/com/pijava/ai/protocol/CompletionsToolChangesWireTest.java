package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * <b>包 A3c</b>：OpenAI Completions 车道的**工具增删原生渲染**（{@code docs/51 §4.4}）。
 *
 * <p>骨架镜像 pi 自己的 oracle {@code packages/ai/test/transcript-tool-changes.test.ts:286-337}
 * 的三条用例。观测面是**真出站请求体**（{@link RecordingHttpServer}）。</p>
 *
 * <p>⚠️ <b>Kimi 形状</b>：{@code {role:"system", tools:[…]}} 在 openai-java 4.42.0 里
 * **没有类型化对应物**（六个消息变体没有一个带 {@code tools}，也没有 beta 的
 * chat-completions 命名空间）⇒ java 走 SDK 的**未知键直通**（原始 JSON 反序列化）。
 * 通路与逐字节往返证据（含「省略 content 时线上也不出现 content」）见
 * {@code SdkJsonEscapeHatchTest}。</p>
 *
 * <p>⚠️ 与 pi 夹具的一处差异：{@code sections} 的 {@code null} 删除语义
 * （{@code docs/49 §9 R3①}）表达不了，夹具只用 content ＋ 一个非空 section。</p>
 */
class CompletionsToolChangesWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolDefinition BASE_TOOL = tool("base_tool");
    private static final ToolDefinition LATE_TOOL = tool("late_tool");

    /** pi 的 {@code additionContext}（{@code :56-62}）：纯增量、**无 sections** ⇒ 可锚定。 */
    private static List<Message> additionTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), Map.of(),
                List.of(LATE_TOOL), List.of()));
    }

    /** pi 的 {@code context}（{@code :40-54}）：中途那条**同时**删 {@code base_tool} ⇒ 非增量。 */
    private static List<Message> removalTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), rules(),
                List.of(LATE_TOOL), List.of(new com.pijava.ai.api.ToolReference("base_tool"))));
    }

    /**
     * 与 {@link #additionTranscript()} 同形，但中途那条**带 section** —— 这是
     * 「前导走完整提示、其余走分段差分更新」那条规则的靶心（无 section 时两者输出相同）。
     */
    private static List<Message> sectionsTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), rules(),
                List.of(LATE_TOOL), List.of()));
    }

    /**
     * pi oracle 用例 9 —— 中途那条系统消息**产出两条**线上消息：先 Kimi 工具消息
     * （**没有 content**），再普通指令消息。
     */
    @Test
    void anchorsKimiAdditionsInToolBearingSystemMessages() throws Exception {
        var body = capture(model(true), additionTranscript());

        assertThat(toolNames(body.path("tools"))).containsExactly("base_tool");
        var kimi = firstMessageWithTools(body);
        assertThat(kimi).as("必须落一条带 tools 的系统消息").isNotNull();
        assertThat(kimi.path("role").asText()).isEqualTo("system");
        assertThat(toolNames(kimi.path("tools"))).containsExactly("late_tool");
        assertThat(kimi.path("content").isMissingNode())
            .as("Kimi 工具消息**没有** content 键（pi :1241-1244 只写 role ＋ tools）").isTrue();
        // 系统消息的 content 序列：前导文本、Kimi（undefined ⇒ 缺席）、中途文本。
        assertThat(systemContents(body)).containsExactly("base prompt", null, "updated guidance");
    }

    /**
     * pi oracle 用例 10 —— 只给 {@code supportsMidConvoSystemMessages}（不给 toolAdditions）
     * ⇒ **没有** tools 消息，两个工具都在请求级。
     */
    @Test
    void keepsSystemTextInlineWithoutDynamicToolMessages() throws Exception {
        var body = capture(model(false), additionTranscript());

        assertThat(toolNames(body.path("tools"))).containsExactly("base_tool", "late_tool");
        assertThat(firstMessageWithTools(body)).isNull();
        assertThat(systemContents(body)).containsExactly("base prompt", "updated guidance");
    }

    /** pi oracle 用例 11 —— 两个标志都不给 ⇒ 折叠成一条 system ＋ user。 */
    @Test
    void foldsUpdatesIntoTheSystemPromptWithoutNativeSupport() throws Exception {
        var body = capture(model(ModelCompat.NONE), removalTranscript());

        assertThat(toolNames(body.path("tools"))).containsExactly("late_tool");
        assertThat(roleNames(body)).containsExactly("system", "user");
        assertThat(systemContents(body))
            .containsExactly("base prompt\n\nupdated guidance\n\n<rules>\nnew rules\n</rules>");
    }

    /**
     * pi {@code :1249} 的 {@code i === 0 ? getSystemMessageText : renderSystemMessageUpdate}
     * —— 中途那条带 section 时必须走**分段差分**渲染（带 {@code Updated system prompt section}
     * 框），而不是把完整提示拼一遍。
     */
    @Test
    void laterSystemMessagesUseTheSectionUpdateRenderer() throws Exception {
        var body = capture(model(true), sectionsTranscript());

        assertThat(systemContents(body)).containsExactly("base prompt", null,
            "updated guidance\n\nUpdated system prompt section \"rules\":\n\n<rules>\nnew rules\n</rules>");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static JsonNode capture(ModelInfo model, List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "OPENAI_API_KEY");
            var request = new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到，流怎么结束与断言无关
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    /** 第一条带 {@code tools} 的消息（role 不限于 system —— 这里只有 Kimi 会带）。 */
    private static JsonNode firstMessageWithTools(JsonNode body) {
        for (var message : body.path("messages")) {
            if (message.has("tools")) {
                return message;
            }
        }
        return null;
    }

    /** 系统消息的 content，按序；**没有 content 键**的记 {@code null}（pi 的 undefined）。 */
    private static List<String> systemContents(JsonNode body) {
        var out = new ArrayList<String>();
        for (var message : body.path("messages")) {
            if (!"system".equals(message.path("role").asText())) {
                continue;
            }
            out.add(message.has("content") ? message.path("content").asText() : null);
        }
        return out;
    }

    private static List<String> toolNames(JsonNode array) {
        var out = new ArrayList<String>();
        for (var tool : array) {
            out.add(tool.path("function").path("name").asText());
        }
        return out;
    }

    private static List<String> roleNames(JsonNode body) {
        var out = new ArrayList<String>();
        for (var message : body.path("messages")) {
            out.add(message.path("role").asText());
        }
        return out;
    }

    /** {@code midConversation} 控制中途系统消息；{@code toolAdditions} 决定 Kimi 形状。 */
    private static ModelInfo model(boolean toolAdditions) {
        return model(new ModelCompat(false, null, true, false, true, toolAdditions, null, null, null));
    }

    private static ModelInfo model(ModelCompat compat) {
        return new ModelInfo(ModelId.of("moonshotai", "kimi-k3"), "Kimi K3", Set.of(),
            100_000, 1000, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);
    }

    private static Map<String, String> rules() {
        var sections = new LinkedHashMap<String, String>();
        sections.put("rules", "<rules>\nnew rules\n</rules>");
        return sections;
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, name + " tool", Map.of("type", "object"));
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
