package com.pijava.ai.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.MaxTokensField;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-01 端到端的钉子：缓存断点真的落到了 Anthropic 的**出站体**上。
 *
 * <p><b>oracle 是跑出来的，不是读出来的</b>：逐条对应 {@code 原 docs/54 §7.1} 的 pi 探针
 * P1–P18 —— 那份用 {@code streamSimple} ＋ {@code onPayload} 抓真实的请求体。本夹具的
 * 每条注释标出 P 编号，方便与 pi 的逐字输出对照。</p>
 *
 * <p>⚠️ <b>断言一律按键取值，不逐字节比对</b>（{@code 原 docs/54 §4.7}）：SDK 的类型化路径
 * 序列化键序与 pi 不同（{@code {text,type,…}} vs {@code {type,text,…}}），JSON 对象键序
 * 无语义。唯一的例外是原始 JSON 直通路径（{@code tool_addition}），它**与 pi 逐字节相同**
 * —— 但本夹具仍只按键取值，逐字节那条由 {@code SdkJsonEscapeHatchTest} 承担。</p>
 *
 * <p>与 {@code AnthropicCacheControlTest} 的分工：那份钉**解析结果**（值），本份钉
 * **落线位置**（哪一条消息的哪一个块）。分开的理由与 A7a/A7c 同 —— 值错了与位置错了是
 * 两类缺陷，混在一份夹具里红因分不清。</p>
 */
class AnthropicCacheControlWireTest {

    private static final String EPHEMERAL = "ephemeral";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 前导系统提示（走 legacy 形参；transcript 已有前导系统消息时不会重复注入）。 */
    private static final String SYSTEM_PROMPT = "be brief";

    // ── 夹具 ────────────────────────────────────────────────────

    private static ModelInfo model(ModelCompat compat) {
        return new ModelInfo(ModelId.of("test-anthropic", "claude-opus-4-8"), "Claude Opus 4.8",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING), 200_000, 32_000, true,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, name + " tool", Map.of("type", "object"));
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    /** 旧形状：无 cache_control 的 compat（两个门走缺省 true）。 */
    private static final ModelCompat PLAIN =
        new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, MaxTokensField.MAX_TOKENS, null, null, null);

    /**
     * **真出站体**（走 {@link RecordingHttpServer}，与 A2/A3 的 wire 夹具同一观测面）。
     *
     * <p>⚠️ 不能用「反射调 {@code buildParams} ＋ 把 SDK 对象序列化」那条路：SDK 的
     * {@code MessageCreateParams} 把请求包在 {@code body} 里，直接
     * {@code writeValueAsString(params)} 得到的是 <b>{@code {}}</b>（实测）。
     * 观测面选真字节还有额外的价值 —— 「请求真的发出去了」本身就被断言了。</p>
     */
    private static JsonNode body(ModelInfo model, List<Message> messages,
                                 List<ToolDefinition> tools, CacheRetention retention)
            throws Exception {
        var extra = new LinkedHashMap<String, Object>();
        if (retention != null) {
            extra.put("cacheRetention", retention.wireName());
        }
        return bodyWith(model, messages, tools, extra);
    }

    private static JsonNode bodyWith(ModelInfo model, List<Message> messages,
                                     List<ToolDefinition> tools, Map<String, Object> extra)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, extra),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, SYSTEM_PROMPT, messages, tools,
                -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到，流怎么结束与断言无关
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    private static JsonNode systemBlock(JsonNode body, int index) {
        return body.path("system").get(index);
    }

    private static JsonNode lastTool(JsonNode body) {
        return body.path("tools").get(body.path("tools").size() - 1);
    }

    private static JsonNode lastBlock(JsonNode body) {
        var messages = body.path("messages");
        var content = messages.get(messages.size() - 1).path("content");
        return content.isArray() ? content.get(content.size() - 1) : content;
    }

    private static void assertEphemeral(JsonNode node, String where, String ttl) {
        var cc = node.path("cache_control");
        assertThat(cc.path("type").asText()).as("%s 的 cache_control.type", where)
            .isEqualTo(EPHEMERAL);
        if (ttl == null) {
            assertThat(cc.has("ttl")).as("%s 不该有 ttl", where).isFalse();
        } else {
            assertThat(cc.path("ttl").asText()).as("%s 的 ttl", where).isEqualTo(ttl);
        }
    }

    private static void assertNoBreakpoint(JsonNode node, String where) {
        assertThat(node.has("cache_control")).as("%s 不该有 cache_control", where).isFalse();
    }

    // ── 形状：system 恒为块数组（P18 ＋ B89）──────────────────────

    @Test
    void systemIsABlockArrayEvenWhenCachingIsOff() throws Exception {
        // P18：pi 在 `cacheRetention:"none"` 时**也是**块数组，只是块上没有断点。
        // ⇒ B89 的修法与缓存无关，是**无条件**的线形状对齐。
        var body = body(model(PLAIN), List.of(user("hi")), List.of(),
            CacheRetention.NONE);

        assertThat(body.path("system").isArray()).isTrue();
        assertThat(systemBlock(body, 0).path("type").asText()).isEqualTo("text");
        assertThat(systemBlock(body, 0).path("text").asText()).isEqualTo("be brief");
        assertNoBreakpoint(systemBlock(body, 0), "system[0]");
    }

    // ── 三处落点（P1/P2/P3）──────────────────────────────────────

    @Test
    void defaultSendsBreakpointsOnSystemTheLastToolAndTheLastMessage() throws Exception {
        // P1：默认（short）就发断点，三处都在，且都没有 ttl。
        var body = body(model(PLAIN), List.of(user("hi")), List.of(tool("a")), null);

        assertEphemeral(systemBlock(body, 0), "system[0]", null);
        assertEphemeral(lastTool(body), "末工具", null);
        assertEphemeral(lastBlock(body), "末消息末块", null);
    }

    @Test
    void noneRemovesEveryBreakpoint() throws Exception {
        // P2：`none` ⇒ 三处全撤（**断点**全撤 —— 与「整条消息消失」是两回事，故三处都断）。
        //
        // ⚠️ 与 pi 的一处**既有**形状差异在这里可见且**不由本包引入**：pi 在 none 时
        // 末条 user 的 content 退回**字符串**（它只在有 cacheControl 时才做「串 → 块数组」
        // 的就地转换），而 java 的用户消息**恒为块数组**（转换器不认识「串」这种形态）。
        // 本包不碰它（形状收敛归 A-18 的长尾），故这里**只**断块上没有断点。
        var body = body(model(PLAIN), List.of(user("hi")), List.of(tool("a")),
            CacheRetention.NONE);

        assertNoBreakpoint(systemBlock(body, 0), "system[0]");
        assertNoBreakpoint(lastTool(body), "末工具");
        assertNoBreakpoint(lastBlock(body), "末消息末块");
    }

    @Test
    void longAddsTheOneHourTtlToEveryBreakpoint() throws Exception {
        // P3：long ⇒ 三处都带 ttl:"1h"。
        var body = body(model(PLAIN), List.of(user("hi")), List.of(tool("a")),
            CacheRetention.LONG);

        assertEphemeral(systemBlock(body, 0), "system[0]", "1h");
        assertEphemeral(lastTool(body), "末工具", "1h");
        assertEphemeral(lastBlock(body), "末消息末块", "1h");
    }

    // ── 两个门（P5/P6）──────────────────────────────────────────

    @Test
    void supportsCacheControlOnToolsFalseDropsOnlyTheToolBreakpoint() throws Exception {
        // P5：门为假 ⇒ **整表**不挂，但 system 与消息照挂。
        // 配对（下一条）保住「不是所有断点都消失」——只断工具的话，把三处一起关掉也会绿。
        var gated = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, MaxTokensField.MAX_TOKENS, null, null, null, null, Boolean.FALSE);

        var body = body(model(gated), List.of(user("hi")), List.of(tool("a"), tool("b")),
            CacheRetention.LONG);

        assertNoBreakpoint(lastTool(body), "末工具");
        assertEphemeral(systemBlock(body, 0), "system[0]", "1h");
        assertEphemeral(lastBlock(body), "末消息末块", "1h");
    }

    @Test
    void supportsCacheControlOnToolsTrueIsTheDefault() throws Exception {
        // 配对：同一个请求、门缺席 ⇒ 工具上有断点。
        var body = body(model(PLAIN), List.of(user("hi")), List.of(tool("a"), tool("b")),
            CacheRetention.LONG);

        assertEphemeral(lastTool(body), "末工具", "1h");
    }

    @Test
    void supportsLongCacheRetentionFalseDropsOnlyTheTtl() throws Exception {
        // P6：门为假 ⇒ 断点**仍在**，只是没有 ttl。
        var gated = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, MaxTokensField.MAX_TOKENS, null, null, null, Boolean.FALSE, null);

        var body = body(model(gated), List.of(user("hi")), List.of(tool("a")),
            CacheRetention.LONG);

        assertEphemeral(systemBlock(body, 0), "system[0]", null);
        assertEphemeral(lastTool(body), "末工具", null);
        assertEphemeral(lastBlock(body), "末消息末块", null);
    }

    // ── 工具表：末项（P7）────────────────────────────────────────

    @Test
    void onlyTheLastToolGetsTheBreakpoint() throws Exception {
        // pi `:1489` 的 `index === tools.length - 1`：前两个都不挂。
        var body = body(model(PLAIN), List.of(user("hi")),
            List.of(tool("a"), tool("b"), tool("c")), null);

        assertNoBreakpoint(body.path("tools").get(0), "工具[0]");
        assertNoBreakpoint(body.path("tools").get(1), "工具[1]");
        assertEphemeral(body.path("tools").get(2), "工具[2]", null);
    }

    private static Message assistant(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    @Test
    void theNativeToolShapePutsTheBreakpointOnTheLastInitialToolOnly() throws Exception {
        // P7/P10：原生支挂 **initialTools 的末项**；占位符与迟到工具都不挂。
        // ⚠️ 所以断点可能落在工具表的**中间** —— 用两个初始工具把这一点钉死
        //（只用一个初始工具时它同时是「首个」与「末项」，分不清是哪个判据）。
        var native_ = new ModelCompat(false, null, true, false, Boolean.TRUE, null,
            Boolean.TRUE, null, null, true, MaxTokensField.MAX_TOKENS, null, null, null);
        var baseA = tool("base_a");
        var baseB = tool("base_b");
        var late = tool("late_tool");
        var messages = List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(baseA, baseB), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), Map.of(),
                List.of(late), List.of(new com.pijava.ai.api.ToolReference("base_a"))));

        var body = body(model(native_), messages, List.of(), null);

        assertThat(body.path("tools").size()).isEqualTo(4);
        assertNoBreakpoint(body.path("tools").get(0), "initialTools[0]");
        assertEphemeral(body.path("tools").get(1), "initialTools 的末项", null);
        assertNoBreakpoint(body.path("tools").get(2), "占位符");
        assertNoBreakpoint(body.path("tools").get(3), "迟到工具");
    }

    // ── 消息表：末条/末块（P8/P10/P13）────────────────────────────

    @Test
    void anAssistantLastMessageGetsNoBreakpointAtAll() throws Exception {
        // P8：末条是 assistant ⇒ 消息这一处**一个字都不挂**（连串都没转）。
        var messages = List.of(user("hi"), assistant("hello"));

        var body = body(model(PLAIN), messages, List.of(), null);

        assertThat(body.path("messages").get(1).path("content").isArray()).isTrue();
        assertNoBreakpoint(body.path("messages").get(0), "u 消息");
        assertNoBreakpoint(body.path("messages").get(1).path("content").get(0), "assistant 末块");
        // 但 system 与工具照挂 —— 配对，保住上面不是「三处一起关了」。
        assertEphemeral(systemBlock(body, 0), "system[0]", null);
    }

    @Test
    void theBreakpointLandsOnTheLastToolResultBlock() throws Exception {
        // P13：末条是归并后的 toolResult user 消息 ⇒ 断点落在 tool_result 块上
        //（白名单含 tool_result）。
        var call = new ContentBlock.ToolUseContent("t1", "lookup", Map.of("value", "x"));
        var messages = List.of(
            user("run it"),
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("calling"), call)),
            new Message.ToolResultMessage("t1", "lookup",
                List.of(new ContentBlock.TextContent("done")), false));

        var body = body(model(PLAIN), messages, List.of(), null);

        var block = lastBlock(body);
        assertThat(block.path("type").asText()).isEqualTo("tool_result");
        assertEphemeral(block, "tool_result 块", null);
    }

    @Test
    void theBreakpointLandsOnTheToolAdditionAfterThePendingSystemFlush() throws Exception {
        // P10/P11：pi 的断点判定发生在 `flushPendingSystemMessages()` **之后** ⇒ 转录末尾
        // 刷出的 held 系统消息成为「最后一条」，断点落在它的末块（`tool_addition`）上。
        // ⚠️ 这是 `原 docs/51 §4.4 ⑥` 那个落点，也是「判定顺序」的唯一可观察后果。
        var native_ = new ModelCompat(false, null, true, false, Boolean.TRUE, null,
            Boolean.TRUE, null, null, true, MaxTokensField.MAX_TOKENS, null, null, null);
        var base = tool("base_tool");
        var late = tool("late_tool");
        var messages = List.of(
            new Message.SystemMessage("base prompt", Instant.EPOCH, Map.of(),
                List.of(base), List.of()),
            user("before"),
            new Message.SystemMessage("updated guidance", Instant.ofEpochMilli(2), Map.of(),
                List.of(late), List.of(new com.pijava.ai.api.ToolReference("base_tool"))));

        var body = body(model(native_), messages, List.of(), null);

        var messagesNode = body.path("messages");
        assertThat(messagesNode.get(messagesNode.size() - 1).path("role").asText())
            .isEqualTo("system");
        var block = lastBlock(body);
        assertThat(block.path("type").asText()).isEqualTo("tool_addition");
        assertEphemeral(block, "tool_addition 块", null);
        // 同一条消息里的前两块（text / tool_removal）不挂 —— 只有**末块**拿断点。
        var content = messagesNode.get(messagesNode.size() - 1).path("content");
        assertNoBreakpoint(content.get(0), "text 块");
        assertNoBreakpoint(content.get(1), "tool_removal 块");
    }

    // ── 可达性：内置目录的真模型（不是手搓 compat）────────────────

    @Test
    void aBuiltInAnthropicModelGetsBreakpointsByDefault() throws Exception {
        // A7c 的教训（原 docs/53 §4.5）：手搓 compat 会把「解析层有没有真的填」遮住。
        // 用真模型走一遍，钉住整条链：目录 → ModelInfo → resolver → 三处落点。
        //
        // ⚠️ 内置 opus-4-8 带着两个 mid-convo 标志（A7b 的目录标注）⇒ 前导系统消息里有
        // toolsAdded 时走**原生工具支**：断点在 initialTools 的末项上，占位符与迟到工具都不挂。
        // 这正是「手搓 compat 遮不住」的那一半 —— 本用例因此比简单形状强一档。
        var builtIn = BuiltinCatalog.anthropicModels()
            .find(ModelId.of("anthropic", "claude-opus-4-8")).orElseThrow();

        var body = body(builtIn, List.of(user("hi")), List.of(tool("a")),
            CacheRetention.LONG);

        assertEphemeral(systemBlock(body, 0), "system[0]", "1h");
        assertEphemeral(body.path("tools").get(0), "initialTools 的末项", "1h");
        assertNoBreakpoint(body.path("tools").get(1), "占位符");
        assertEphemeral(lastBlock(body), "末消息末块", "1h");
    }

    @Test
    void aStringValuedOptionOnTheWireIsAcceptedToo() throws Exception {
        // 选项通道两种来源：java 内代码塞枚举值、models.json/CLI 塞线格名。上面所有用例走的是
        // 前者（`body` 把枚举翻成 wireName），这一条直接塞串，钉住后者。
        var extra = new LinkedHashMap<String, Object>();
        extra.put("cacheRetention", "long");

        var body = bodyWith(model(PLAIN), List.of(user("hi")), List.of(tool("a")), extra);

        assertEphemeral(systemBlock(body, 0), "system[0]", "1h");
        assertEphemeral(lastTool(body), "末工具", "1h");
    }

    @Test
    void anUnknownRetentionStringFallsBackInsteadOfSilentlyDisablingCaching() throws Exception {
        // ⚠️ 反面钉子：合法集合外的串（"1h"）当作**缺席**，于是 default short 生效 ——
        // 断点仍发（不是消失）、且没有 ttl。若 parse 塌成 SHORT，这一条同样绿，
        // 但那时 PI_CACHE_RETENTION 会被静默屏蔽（原 docs/54 §4.2）—— 那种差别只有
        // CacheRetentionTest 的环境变量用例能看见，两份夹具各管一半。
        // ⚠️ 大小写**不**算非法：选项侧的 parse 是小写化的（java 的配置面方言，
        // pi 的 TS 类型让这种输入不可能出现）—— 见 parseIsCaseInsensitiveForTheOptionSideOnly。
        var body = bodyWith(model(PLAIN), List.of(user("hi")), List.of(tool("a")),
            Map.of("cacheRetention", "1h"));

        assertEphemeral(systemBlock(body, 0), "system[0]", null);
    }
}
