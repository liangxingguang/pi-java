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
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * <b>包 A3c</b>：Anthropic 车道的**工具增删原生渲染**（{@code docs/51 §4.4}）。
 *
 * <p>骨架镜像 pi 自己的 oracle {@code packages/ai/test/transcript-tool-changes.test.ts:73-186}
 * 的四条用例。观测面是**真出站请求体**（{@link RecordingHttpServer}）。</p>
 *
 * <p>⚠️ <b>门的判据与 Responses **不同**</b>（这是最容易抄错的一处）：Anthropic 的四条件门
 * 只排除**重定义**（{@code hasToolRedefinitions}，{@code :1055}）—— 删除完全没问题，
 * 那正是 {@code tool_removal} 块的用途；Responses 的 {@code anchorsAdditions} 则用
 * {@code hasNonAdditiveToolChanges}，**任何删除都会关掉锚定**。两者的夹具因此用不同的上下文。</p>
 *
 * <p>⚠️ <b>与 pi 夹具的三处有意差异</b>：</p>
 *
 * <ul>
 *   <li><b>顶层 {@code system} 是字符串而不是块数组</b>（{@code docs/32} **B89**）：
 *       pi 的 {@code system} 恒为 {@code [{type:"text",…}]}，java 走 SDK 的
 *       {@code builder.system(String)} ⇒ 线上是 {@code "system":"…"}。块形态与
 *       {@code cache_control} 一并归 A-01。</li>
 *   <li><b>没有 {@code cache_control}</b>（A-01）：pi 的 oracle 断言第一个工具带
 *       {@code cache_control:{type:"ephemeral"}}、且断点可落在 {@code tool_addition}/
 *       {@code tool_removal} 上 —— java 侧 {@code docs/51 §4.4 ⑥} 无从落，
 *       本夹具只断言 {@code defer_loading} 的有无。</li>
 *   <li><b>{@code sections} 的 {@code null} 删除语义</b>（{@code docs/49 §9 R3①}）：
 *       夹具只用 content ＋ 一个非空 section。</li>
 * </ul>
 */
class AnthropicToolChangesWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BETA = "mid-conversation-tool-changes-2026-07-01";

    private static final ToolDefinition BASE_TOOL = tool("base_tool");
    private static final ToolDefinition LATE_TOOL = tool("late_tool");

    /**
     * pi 的 {@code context}（{@code :40-54}）：中途那条**同时**删 {@code base_tool}、
     * 加 {@code late_tool} ⇒ 对 Anthropic **仍是原生支**（它只拒绝重定义）。
     */
    private static List<Message> removalTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), rules(),
                List.of(LATE_TOOL), List.of(new ToolReference("base_tool"))));
    }

    /** 只有前两条消息 —— 用来钉「占位符从第一个请求起就在」。 */
    private static List<Message> initialTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"));
    }

    /** pi oracle 用例 1 —— 三方都在场：beta 头、工具表三段式、更新块的三块序。 */
    @Test
    void sendsToolChangesInANativeSystemMessage() throws Exception {
        var body = capture(nativeModel(), removalTranscript());

        assertThat(betas(body)).contains(BETA);
        assertThat(body.path("system").asText()).isEqualTo("base prompt");
        // 三段式：初始（活跃）＋ 占位符（deferred）＋ 后续声明（deferred）。
        assertThat(toolNames(body)).containsExactly(
            "base_tool", "__pi_deferred_placeholder__", "late_tool");
        assertThat(tool(body, 0).path("defer_loading").isMissingNode())
            .as("初始工具必须**活跃** —— Anthropic 拒绝「全部 deferred」的工具表").isTrue();
        assertThat(tool(body, 1).path("defer_loading").asBoolean()).isTrue();
        assertThat(tool(body, 2).path("defer_loading").asBoolean()).isTrue();

        // 最后一条消息：块序 [text, tool_removal, tool_addition]（pi :1250-1270）。
        var blocks = lastContent(body);
        assertThat(blockTypes(blocks)).containsExactly("text", "tool_removal", "tool_addition");
        assertThat(blocks.get(0).path("text").asText())
            .contains("updated guidance")
            .contains("Updated system prompt section \"rules\":\n\n<rules>\nnew rules\n</rules>");
        assertThat(blocks.get(1).path("tool").path("name").asText()).isEqualTo("base_tool");
        assertThat(blocks.get(2).path("tool").path("name").asText()).isEqualTo("late_tool");
    }

    /** 「删了的工具**仍然声明**」（靠 `tool_removal` 撤回）—— 请求级列表只增不减。 */
    @Test
    void removedToolsStayDeclaredInTheRequestLevelList() throws Exception {
        var body = capture(nativeModel(), removalTranscript());

        assertThat(toolNames(body)).containsExactly(
            "base_tool", "__pi_deferred_placeholder__", "late_tool");
        assertThat(toolNames(body)).as("被删的 base_tool 仍在请求级列表里")
            .contains("base_tool");
    }

    /** pi oracle 用例 1 的后半 —— 只有前两条消息时，工具表恰是「初始 ＋ 占位符」。 */
    @Test
    void thePlaceholderIsDeclaredFromTheVeryFirstRequest() throws Exception {
        var body = capture(nativeModel(), initialTranscript());

        assertThat(toolNames(body)).containsExactly("base_tool", "__pi_deferred_placeholder__");
        assertThat(lastRole(body)).as("还没发生工具变更 ⇒ 末尾仍是 user 消息").isEqualTo("user");
    }

    /** pi oracle 用例 2 —— 同名**重定义**表达不了（块只按名引用）⇒ 关掉原生支。 */
    @Test
    void fallsBackWhenTheHistoryHasAToolRedefinition() throws Exception {
        var transcript = List.<Message>of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), Map.of(),
                List.of(tool("base_tool", "changed")), List.of()));

        var body = capture(nativeModel(), transcript);

        assertThat(betas(body)).doesNotContain(BETA);
        assertThat(toolNames(body)).containsExactly("base_tool");
        assertThat(tool(body, 0).path("description").asText()).isEqualTo("changed");
        assertThat(tool(body, 0).path("defer_loading").isMissingNode()).isTrue();
        // pi oracle：回退支下中途系统消息**仍落线**，但只有 text 块（没有 tool_removal/addition）
        // —— 模型支持中途系统消息，只是表达不了这段工具史。
        assertThat(lastRole(body)).isEqualTo("system");
        assertThat(blockTypes(lastContent(body))).containsExactly("text");
    }

    /**
     * pi oracle 用例 2 的第二个回退上下文 —— **没有初始工具**。
     *
     * <p>四条件门的第三条（{@code initialTools.length > 0}）就是为它：Anthropic **拒绝**
     * 一张「全部 deferred」的工具表，所以必须有活跃工具做锚点。</p>
     */
    @Test
    void fallsBackWhenThereIsNoInitialToolToAnchorTheDeferredOnes() throws Exception {
        var transcript = List.<Message>of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(), List.of(), List.of()),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), Map.of(),
                List.of(LATE_TOOL), List.of()));

        var body = capture(nativeModel(), transcript);

        assertThat(betas(body)).doesNotContain(BETA);
        assertThat(toolNames(body)).as("回退到完整当前表；没有占位符、没有 deferred")
            .containsExactly("late_tool");
        assertThat(tool(body, 0).path("defer_loading").isMissingNode()).isTrue();
    }

    /**
     * pi {@code :1236-1240} 的**攒批**理由：Anthropic 要求 {@code tool_result} 紧跟
     * {@code tool_use}，夹一条系统消息会被拒 ⇒ 转录里排在 user 消息前的更新，
     * **线上落到它之后**。
     *
     * <p>靶心是位置：转录 {@code [前导, userA, 中途更新, userB]} 在线上必须是
     * {@code [userA, userB, system]}（更新攒到转录末尾刷出），**不是** {@code [userA, system, userB]}。</p>
     */
    @Test
    void holdsSystemUpdatesBackUntilTheNextAssistantMessage() throws Exception {
        var transcript = List.<Message>of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), Map.of(),
                List.of(LATE_TOOL), List.of()),
            user("after"));

        var body = capture(nativeModel(), transcript);

        assertThat(roleNames(body)).containsExactly("user", "user", "system");
        assertThat(blockTypes(lastContent(body))).containsExactly("text", "tool_addition");
    }

    /** pi oracle 用例 3 —— **只给 {@code supportsMidConvoToolChanges}** ⇒ 退回折叠。 */
    @Test
    void requiresBothCapabilitiesForNativeToolChanges() throws Exception {
        var compat = new ModelCompat(false, null, true, false, null, null, true, null, null);
        var body = capture(model(compat), removalTranscript());

        assertThat(betas(body)).doesNotContain(BETA);
        assertThat(toolNames(body)).containsExactly("late_tool");
        assertThat(roleNames(body)).containsExactly("user");
    }

    /** pi oracle 用例 4 —— 两个标志都不给 ⇒ 折叠成一条前导提示。 */
    @Test
    void foldsUpdatesIntoTheSystemPromptWithoutNativeSupport() throws Exception {
        var body = capture(model(ModelCompat.NONE), removalTranscript());

        assertThat(body.path("system").asText())
            .isEqualTo("base prompt\n\nupdated guidance\n\n<rules>\nnew rules\n</rules>");
        assertThat(toolNames(body)).containsExactly("late_tool");
        assertThat(roleNames(body)).containsExactly("user");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static JsonNode capture(ModelInfo model, List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
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

    private static JsonNode lastMessage(JsonNode body) {
        return body.path("messages").get(body.path("messages").size() - 1);
    }

    private static JsonNode lastContent(JsonNode body) {
        return lastMessage(body).path("content");
    }

    private static String lastRole(JsonNode body) {
        return lastMessage(body).path("role").asText();
    }

    private static List<String> betas(JsonNode body) {
        var out = new ArrayList<String>();
        for (var b : body.path("betas")) {
            out.add(b.asText());
        }
        return out;
    }

    private static List<String> toolNames(JsonNode body) {
        var out = new ArrayList<String>();
        for (var t : body.path("tools")) {
            out.add(t.path("name").asText());
        }
        return out;
    }

    private static JsonNode tool(JsonNode body, int index) {
        return body.path("tools").get(index);
    }

    private static List<String> blockTypes(JsonNode content) {
        var out = new ArrayList<String>();
        for (var block : content) {
            out.add(block.path("type").asText());
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

    /** 两个标志都给（Anthropic 的原生支需要**都**在场）。 */
    private static ModelInfo nativeModel() {
        return model(new ModelCompat(false, null, true, false, true, null, true, null, null));
    }

    private static ModelInfo model(ModelCompat compat) {
        return new ModelInfo(ModelId.of("anthropic", "claude-opus-5"), "Claude Opus 5", Set.of(),
            100_000, 1000, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), compat);
    }

    private static Map<String, String> rules() {
        var sections = new LinkedHashMap<String, String>();
        sections.put("rules", "<rules>\nnew rules\n</rules>");
        return sections;
    }

    private static ToolDefinition tool(String name) {
        return tool(name, name + " tool");
    }

    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description, Map.of("type", "object"));
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
