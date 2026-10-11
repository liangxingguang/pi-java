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
 * <b>包 A3c</b>：Anthropic 车道的**工具增删原生渲染**（{@code 原 docs/51 §4.4}）。
 *
 * <p><b>包 B165</b>（{@code 原 docs/45}）：形状换成 pi 的**定义内联**
 * （{@code b271b0a52}，beta {@code inline-tools-2026-09-15}）。顶层工具表**就此定死**
 * （初始 ＋ 占位符），后续工具在 {@code tool_addition} 块里带**完整定义**（含
 * {@code input_schema}），同名重定义**不再回退**。骨架镜像 pi 重写后的 oracle
 * {@code packages/ai/test/transcript-tool-changes.test.ts:103-190}。观测面是**真出站请求体**
 * （{@link RecordingHttpServer}）。</p>
 *
 * <p>⚠️ <b>门的判据与 Responses **不同**</b>（这是最容易抄错的一处）：Anthropic 的三条件门
 * 只要求「有初始工具」—— 删除与**同名重定义**都没问题，前者靠 {@code tool_removal} 块、
 * 后者靠重新内联一条 {@code tool_addition}（{@code b271b0a52} 前重定义曾是回退判据）；
 * Responses 的 {@code anchorsAdditions} 则用 {@code hasNonAdditiveToolChanges}，
 * **任何删除都会关掉锚定**。两者的夹具因此用不同的上下文。</p>
 *
 * <p>⚠️ <b>与 pi 夹具的三处有意差异</b>：</p>
 *
 * <ul>
 *   <li>~~<b>顶层 {@code system} 是字符串而不是块数组</b>（{@code docs/05} **B89**）
 *       —— ✅ 已随包 A-01 结案~~：{@code system} 现为 {@code [{type:"text",…}]} 块数组
 *       （pi {@code :1077-1097}），块上带缓存断点。</li>
 *   <li>~~<b>没有 {@code cache_control}</b>（A-01）~~：✅ 已随包 A-01 落地 ——
 *       pi 的 oracle 断言第一个工具带 {@code cache_control:{type:"ephemeral"}}、且断点可落在
 *       {@code tool_addition}/{@code tool_removal} 上（{@code 原 docs/51 §4.4 ⑥} 的那个落点）。
 *       本夹具仍只断言 {@code defer_loading} 的有无 —— 断点的断言在
 *       {@code AnthropicCacheControlWireTest}。</li>
 *   <li><b>{@code sections} 的 {@code null} 删除语义</b>（{@code 原 docs/49 §9 R3①}）：
 *       夹具只用 content ＋ 一个非空 section。</li>
 * </ul>
 */
class AnthropicToolChangesWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BETA = "inline-tools-2026-09-15";

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

    /**
     * pi oracle 用例 1（{@code :104-141}）—— 三方都在场：beta 头、工具表两段式、更新块的三块序。
     *
     * <p>靶心是<b>顶层表就此定死</b>：{@code late_tool} **不在**顶层（旧形状下它在，且
     * {@code defer_loading:true}），它的完整定义改在 {@code tool_addition} 块里内联。</p>
     */
    @Test
    void sendsToolChangesInANativeSystemMessage() throws Exception {
        var body = capture(nativeModel(), removalTranscript());

        assertThat(betas(body)).contains(BETA);
        // pi oracle 逐字：旧 beta 必须**不在**（换形状而非叠形状）。
        assertThat(betas(body))
            .as("旧 beta 必须消失 —— b271b0a52 是换值不是叠加")
            .doesNotContain("mid-conversation-tool-changes-2026-07-01");
        // 包 A-01（结案 B89）：顶层 `system` 是**块数组**（pi :1077-1097），块上带缓存断点。
        assertThat(body.path("system").isArray()).isTrue();
        assertThat(body.path("system").get(0).path("type").asText()).isEqualTo("text");
        assertThat(body.path("system").get(0).path("text").asText()).isEqualTo("base prompt");
        // 两段式：初始（活跃）＋ 占位符（deferred）。**后续工具不再摊在这里**。
        assertThat(toolNames(body)).containsExactly(
            "base_tool", "__pi_deferred_placeholder__");
        assertThat(toolNames(body)).as("顶层表长度必须是 2 —— 后续工具的定义内联在块里")
            .hasSize(2);
        assertThat(tool(body, 0).path("defer_loading").isMissingNode())
            .as("初始工具必须**活跃** —— Anthropic 拒绝「全部 deferred」的工具表").isTrue();
        assertThat(tool(body, 1).path("defer_loading").asBoolean()).isTrue();

        // 最后一条消息：块序 [text, tool_removal, tool_addition]（pi :1250-1270）。
        var blocks = lastContent(body);
        assertThat(blockTypes(blocks)).containsExactly("text", "tool_removal", "tool_addition");
        assertThat(blocks.get(0).path("text").asText())
            .contains("updated guidance")
            .contains("Updated system prompt section \"rules\":\n\n<rules>\nnew rules\n</rules>");
        // tool_removal 的载荷**没变** —— 仍是按名引用。
        assertThat(blocks.get(1).path("tool").path("type").asText()).isEqualTo("tool_reference");
        assertThat(blocks.get(1).path("tool").path("name").asText()).isEqualTo("base_tool");
        // tool_addition 换成**定义内联**：载荷是 tool_definition ＋ 完整 definition。
        assertThat(blocks.get(2).path("tool").path("type").asText()).isEqualTo("tool_definition");
        assertThat(blocks.get(2).path("tool").path("definition").path("name").asText())
            .isEqualTo("late_tool");
        assertThat(blocks.get(2).path("tool").path("definition").path("description").asText())
            .isEqualTo("late_tool tool");
    }

    /**
     * pi oracle 用例 1 的后半 —— 工具表两段式，且<b>被删的工具不再声明</b>。
     *
     * <p>⚠️ 这条替换了旧形状下的 {@code removedToolsStayDeclaredInTheRequestLevelList}：
     * 那句「请求级列表只增不减」在新形状下**不再成立** —— 被删的工具本来就只在顶层出现过
     * （初始工具），而 {@code tool_removal} 撤回的是「相对初始表新加又删掉」的那些，
     * 它们的定义从不进顶层表。</p>
     */
    @Test
    void laterToolsNeverAppearInTheRequestLevelList() throws Exception {
        var body = capture(nativeModel(), removalTranscript());

        assertThat(toolNames(body)).containsExactly("base_tool", "__pi_deferred_placeholder__");
        assertThat(toolNames(body))
            .as("late_tool 只在 tool_addition 块里内联，不进顶层表")
            .doesNotContain("late_tool");
    }

    /** pi oracle 用例 1 的后半 —— 只有前两条消息时，工具表恰是「初始 ＋ 占位符」。 */
    @Test
    void thePlaceholderIsDeclaredFromTheVeryFirstRequest() throws Exception {
        var body = capture(nativeModel(), initialTranscript());

        assertThat(toolNames(body)).containsExactly("base_tool", "__pi_deferred_placeholder__");
        assertThat(lastRole(body)).as("还没发生工具变更 ⇒ 末尾仍是 user 消息").isEqualTo("user");
    }

    /**
     * pi oracle 用例 2（{@code :143-172}）—— 同名**重定义**按值内联，原生支**照走**。
     *
     * <p>⚠️ 整条是从旧形状的 {@code fallsBackWhenTheHistoryHasAToolRedefinition} **翻转**来的：
     * 旧形状下块只按名引用 ⇒ 表达不了重定义 ⇒ 关掉整支、发完整当前表；新形状下
     * 「同名新定义取代旧定义」就是一条 {@code tool_addition}，**不发** {@code tool_removal}。</p>
     *
     * <p>两个靶心：① 顶层表的声明保留**原始**定义（缓存前缀存活 —— 重定义不改它）；
     * ② 末条系统消息的 content 只有**一个块**（没有 removal）。</p>
     *
     * <p>⚠️ 转录取自 pi oracle 逐字（{@code :149-158}）：{@code toolsRemoved} 与
     * {@code toolsAdded} **两边都给同一个名字** —— 这才是「重定义」在这条状态线上的形状
     * （{@code ToolStateChanges} 的纪律是「先删后加」）。少了 {@code toolsRemoved}，
     * 「同名不发 removal」那条分支就**没有夹具**（探针 M6 零红印证）。</p>
     */
    @Test
    void redefinesAToolByValueUnderTheSameName() throws Exception {
        var transcript = List.<Message>of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(BASE_TOOL), List.of()),
            user("before"),
            new Message.SystemMessage("", Instant.ofEpochMilli(2), Map.of(),
                List.of(tool("base_tool", "changed")),
                List.of(new ToolReference("base_tool"))));

        var body = capture(nativeModel(), transcript);

        assertThat(betas(body)).as("重定义不再是回退判据").contains(BETA);
        // 顶层声明保留**原始**定义 —— 缓存前缀跨重定义存活。
        assertThat(toolNames(body)).containsExactly("base_tool", "__pi_deferred_placeholder__");
        assertThat(tool(body, 0).path("description").asText())
            .as("顶层表保留**原始**定义，不是新的那份").isEqualTo("base_tool tool");
        assertThat(tool(body, 0).path("defer_loading").isMissingNode()).isTrue();
        // 新定义就被内联替换；同名 ⇒ 不发 removal。
        var content = lastContent(body);
        assertThat(blockTypes(content)).containsExactly("tool_addition");
        assertThat(content).as("同名重定义 ⇒ 只有一条 tool_addition，没有 tool_removal")
            .hasSize(1);
        assertThat(content.get(0).path("tool").path("type").asText()).isEqualTo("tool_definition");
        var definition = content.get(0).path("tool").path("definition");
        assertThat(definition.path("name").asText()).isEqualTo("base_tool");
        assertThat(definition.path("description").asText()).isEqualTo("changed");
    }

    /**
     * 顶层表的定义与内联定义**逐字段同源** —— 都出自 pi 的同一个 {@code convertTools}
     * （{@code :1576-1614}）。
     *
     * <p>靶心是「顶层表不再是后续工具的家」这件事**不能**顺带丢掉它的参数形状：
     * 内联 {@code definition} 必须带 {@code input_schema}（否则模型看不到迟到工具的参数），
     * 且必须与顶层表里**同一工具**的 {@code input_schema} 相同。</p>
     *
     * <p>⚠️ 这里顺带钉住一处 SDK 陷阱（探针实测）：{@code Tool.InputSchema} 的声明字段
     * {@code type} 与 additionalProperties **重叠**，走
     * {@code putAllAdditionalProperties} 会让 {@code "type"} 键**写两遍**
     * （{@code "type":"object","type":"object"}）—— 与 pi 的线格不符。断言因此打在
     * <b>原始字节</b>上：被 Jackson 解析成树后重复键会被静默折叠，树级断言看不见它。</p>
     */
    @Test
    void theInlineDefinitionCarriesTheSameSchemaAsTheRequestLevelTool() throws Exception {
        var rich = new LinkedHashMap<String, Object>();
        rich.put("type", "object");
        rich.put("properties", Map.of("path", Map.of("type", "string")));
        rich.put("required", List.of("path"));
        rich.put("additionalProperties", false);

        var transcript = List.<Message>of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(new ToolDefinition("base_tool", "base_tool tool", rich)), List.of()),
            user("before"),
            new Message.SystemMessage("", Instant.ofEpochMilli(2), Map.of(),
                List.of(new ToolDefinition("base_tool", "changed", rich)), List.of()));

        var raw = captureRaw(nativeModel(), transcript);
        var body = MAPPER.readTree(raw);

        assertThat(betas(body)).contains(BETA);
        var topLevel = tool(body, 0).path("input_schema");
        assertThat(topLevel.path("type").asText()).isEqualTo("object");
        assertThat(topLevel.path("additionalProperties").asBoolean()).isFalse();
        assertThat(topLevel.path("required").get(0).asText()).isEqualTo("path");

        var content = lastContent(body);
        var inline = content.get(0).path("tool").path("definition").path("input_schema");
        assertThat(inline)
            .as("内联定义必须带**完整** input_schema，且与顶层表同源")
            .isEqualTo(topLevel);

        // 重复键回归：raw 字节里不许出现两次 type。
        assertThat(raw)
            .as("SDK 的 InputSchema 声明字段与 additionalProperties 重叠 ⇒ 不能走 "
                + "putAllAdditionalProperties（会重复写 type 键）")
            .doesNotContain("\"type\":\"object\",\"type\":\"object\"");
    }

    /**
     * pi oracle 用例 2 的第二个回退上下文 —— **没有初始工具**。
     *
     * <p>三条件门的第三条（{@code initialTools.length > 0}）就是为它：Anthropic **拒绝**
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

        assertThat(body.path("system").get(0).path("text").asText())
            .isEqualTo("base prompt\n\nupdated guidance\n\n<rules>\nnew rules\n</rules>");
        assertThat(toolNames(body)).containsExactly("late_tool");
        assertThat(roleNames(body)).containsExactly("user");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static JsonNode capture(ModelInfo model, List<Message> messages) throws Exception {
        return MAPPER.readTree(captureRaw(model, messages));
    }

    /** 观测面是**原始出站字节** —— 重复键只在这一层看得见（解析成树会被静默折叠）。 */
    private static String captureRaw(ModelInfo model, List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, null, messages, List.of(), -1, -1);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到，流怎么结束与断言无关
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return server.body();
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
