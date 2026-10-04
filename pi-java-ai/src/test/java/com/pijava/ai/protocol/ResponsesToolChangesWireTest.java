package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * <b>包 A3c</b>：Responses 车道的**工具增删原生渲染**（{@code 原 docs/51 §4.4}）。
 *
 * <p>骨架镜像 pi 自己的 oracle {@code packages/ai/test/transcript-tool-changes.test.ts:190-255}
 * 的四条用例（{@code 原 docs/45 §10} 那条「夹具骨架优先镜像 pi 自己的测试」）——
 * 同一份输入、逐条对照期望。观测面是**真出站请求体**（{@link RecordingHttpServer}），
 * 与 {@code ResponsesToolsStrictWireTest}／{@code LaneTransformMessagesSourceTest} 同一装置。</p>
 *
 * <p>⚠️ <b>与 pi 夹具的两处有意差异</b>：</p>
 *
 * <ul>
 *   <li><b>文本项的 role</b>：pi 的 {@code instructionRole} 是
 *       {@code model.reasoning &amp;&amp; compat?.supportsDeveloperRole !== false ? "developer" : "system"}
 *       （{@code openai-responses-shared.ts:213}），oracle 的模型 {@code reasoning:true}
 *       ⇒ 它断言 {@code developer}。java 侧恒发 {@code system}（{@code supportsDeveloperRole}
 *       尚未接进 {@code ModelCompat}，登记 {@code docs/07 A-07} / {@code 原 docs/51 §10 L-E}）
 *       ⇒ 本夹具用 {@code reasoning:false} 的模型，使两侧在这一点上**本来就同形**，
 *       于是「role 差异」不会混进对锚定结构的断言。注意 {@code additional_tools} 项自己的
 *       {@code role:"developer"} 是 pi **写死的**（{@code :188}），java 照抄。</li>
 *   <li><b>sections</b>：pi 的 {@code context} 带 {@code sections}（含 {@code docs: null} 的删除），
 *       java 的 {@code Map<String,String>} 表达不了「值在场但为 null」（{@code 原 docs/49 §9 R3①}）
 *       ⇒ 本夹具只用 {@code content}＋工具字段，文本项对照的是 {@code renderSystemMessageUpdate}
 *       里**能表达的那一支**。</li>
 * </ul>
 */
class ResponsesToolChangesWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolDefinition BASE_TOOL = tool("base_tool");
    private static final ToolDefinition LATE_TOOL = tool("late_tool");

    /**
     * pi 的 {@code additionContext}（{@code :56-62}）：前导声明 {@code base_tool}，
     * 中途一条系统消息追加 {@code late_tool}。**纯增量** ⇒ {@code anchorsAdditions} 为真。
     */
    private static List<Message> additionTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), rules(),
                List.of(LATE_TOOL), List.of()));
    }

    /**
     * pi 的 {@code context}（{@code :40-54}）：中途那条**同时**删 {@code base_tool}、加
     * {@code late_tool} ⇒ 非增量 ⇒ {@code anchorsAdditions} 为假。
     */
    private static List<Message> removalTranscript() {
        return List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), rules(),
                List.of(LATE_TOOL), List.of(new com.pijava.ai.api.ToolReference("base_tool"))));
    }

    /** pi oracle 用例 5 —— {@code additional_tools} 项装载增量工具，请求级只留初始那份。 */
    @Test
    void anchorsAdditionalToolsAtTheirDeveloperMessage() throws Exception {
        var body = capture(model(true, false), additionTranscript());

        assertThat(names(body.path("tools"))).containsExactly("base_tool");
        var anchor = firstItemOfType(body, "additional_tools");
        assertThat(anchor).as("必须落一条 additional_tools 项").isNotNull();
        assertThat(anchor.path("role").asText()).isEqualTo("developer");
        assertThat(names(anchor.path("tools"))).containsExactly("late_tool");
        // 文本项：前导走完整提示、中途走分段差分更新，**顺序**与转录一致。
        assertThat(systemTexts(body)).containsExactly("base prompt", RENDERED_UPDATE);
    }

    /** pi oracle 用例 6 —— 只给 {@code supportsToolSearch} 时合成一对 tool_search。 */
    @Test
    void mapsAdditionsIntoASyntheticToolSearchPair() throws Exception {
        var body = capture(model(false, true), additionTranscript());

        assertThat(names(body.path("tools"))).containsExactly("base_tool");
        var call = firstItemOfType(body, "tool_search_call");
        var output = firstItemOfType(body, "tool_search_output");
        assertThat(call).as("tool_search_call 必须在场").isNotNull();
        assertThat(output).as("tool_search_output 必须在场").isNotNull();
        // 执行方是客户端、状态是完成（pi :196-207 两处都写死）。
        assertThat(call.path("execution").asText()).isEqualTo("client");
        assertThat(call.path("status").asText()).isEqualTo("completed");
        assertThat(output.path("execution").asText()).isEqualTo("client");
        assertThat(output.path("status").asText()).isEqualTo("completed");
        // 一对两半共用同一个 call_id，且它是**确定性**的（种子 ＋ 名字列表的哈希）。
        assertThat(output.path("call_id").asText()).isEqualTo(call.path("call_id").asText());
        assertThat(call.path("call_id").asText()).startsWith("pi_tool_load_");
        // 搜索结果是**延迟装载**的工具（pi :390 的 defer_loading）。
        assertThat(names(output.path("tools"))).containsExactly("late_tool");
        assertThat(output.path("tools").get(0).path("defer_loading").asBoolean()).isTrue();
    }

    /** 同一个转录重放两次 ⇒ 同一个 call_id（缓存前缀与转录都必须可复现）。 */
    @Test
    void theToolSearchCallIdIsDeterministicAcrossReplays() throws Exception {
        var first = capture(model(false, true), additionTranscript());
        var second = capture(model(false, true), additionTranscript());

        assertThat(firstItemOfType(second, "tool_search_call").path("call_id").asText())
            .isEqualTo(firstItemOfType(first, "tool_search_call").path("call_id").asText());
    }

    /** pi oracle 用例 8 —— 有删除 ⇒ 非增量 ⇒ **无**锚定项，但文本项照发。 */
    @Test
    void fallsBackToTheCompleteToolStateWhenRemovalsAreUnsupported() throws Exception {
        var body = capture(model(true, false), removalTranscript());

        assertThat(names(body.path("tools"))).containsExactly("late_tool");
        assertThat(firstItemOfType(body, "additional_tools"))
            .as("锚定不成立 ⇒ 没有 additional_tools 项").isNull();
        assertThat(systemTexts(body)).containsExactly("base prompt", RENDERED_UPDATE);
    }

    /** pi oracle 用例 7 —— 模型不支持中途系统消息 ⇒ 折叠，锚定随之关闭。 */
    @Test
    void foldsWhenTheModelDoesNotAcceptMidConversationSystemMessages() throws Exception {
        var body = capture(model(true, false, false), removalTranscript());

        assertThat(names(body.path("tools"))).containsExactly("late_tool");
        assertThat(firstItemOfType(body, "additional_tools")).isNull();
        // 折叠后只剩一条前导系统文本：content 逐条拼、再拼 section 值、空段滤掉
        // （pi 的 getSystemMessageText，text.ts:15-21）—— 与上面的 RENDERED_UPDATE
        // 明显不同，正是「前导走完整提示、其余走分段更新」这条规则的靶心。
        assertThat(systemTexts(body))
            .containsExactly("base prompt\n\nupdated guidance\n\n<rules>\nnew rules\n</rules>");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static JsonNode capture(ModelInfo model, List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(
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

    /** input 里第一条 {@code type} 等于给定值的项（没有则 null）。 */
    private static JsonNode firstItemOfType(JsonNode body, String type) {
        for (var item : body.path("input")) {
            if (type.equals(item.path("type").asText())) {
                return item;
            }
        }
        return null;
    }

    /**
     * input 里**系统文本项**的内容，按序。
     *
     * <p>pi 的 oracle 用 {@code item.type === undefined &amp;&amp; item.role === "developer"}
     * 挑出它们（{@code :218}）；java 侧的 role 是 {@code system}（{@code instructionRole}
     * 的 A-07 缺口，见类注释）⇒ 这里按 {@code role == "system"} 挑，且必须排掉
     * **user** 项（它同样没有 {@code type}）。</p>
     */
    private static List<String> systemTexts(JsonNode body) {
        var texts = new ArrayList<String>();
        for (var item : body.path("input")) {
            if (!item.has("type") && "system".equals(item.path("role").asText())) {
                texts.add(item.path("content").asText());
            }
        }
        return texts;
    }

    private static List<String> names(JsonNode array) {
        var names = new ArrayList<String>();
        for (var item : array) {
            names.add(item.path("name").asText());
        }
        return names;
    }

    /**
     * {@code reasoning:false}（避开 {@code instructionRole} 的 A-07 缺口）＋
     * {@code supportsMidConvoSystemMessages:true}（不折叠）＋ 两个锚定机制之一。
     */
    private static ModelInfo model(boolean additionalTools, boolean toolSearch) {
        return model(additionalTools, toolSearch, true);
    }

    private static ModelInfo model(boolean additionalTools, boolean toolSearch,
                                   boolean midConversation) {
        return new ModelInfo(ModelId.of("openai", "gpt-5.4"), "GPT-5.4", Set.of(),
            100_000, 16_384, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(),
            new ModelCompat(false, null, true, false, midConversation, null, null,
                additionalTools, toolSearch));
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, name + " tool", Map.of("type", "object"));
    }

    /**
     * 中途系统消息带的 section —— 它的**存在**是 {@code renderSystemMessageUpdate}
     * 那一支的靶心：没有 section 时它和 {@code getSystemMessageText} 的输出相同，
     * 「非前导走 update 渲染」这条规则就无从验证（pi 的 oracle 同样用
     * {@code sections.rules} 区分两者）。
     */
    private static Map<String, String> rules() {
        var sections = new java.util.LinkedHashMap<String, String>();
        sections.put("rules", "<rules>\nnew rules\n</rules>");
        return sections;
    }

    /** 中途系统消息的线上文本 —— pi {@code text.ts:23-40} 的「按名框住」渲染。 */
    private static final String RENDERED_UPDATE =
        "updated guidance\n\nUpdated system prompt section \"rules\":\n\n<rules>\nnew rules\n</rules>";

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
