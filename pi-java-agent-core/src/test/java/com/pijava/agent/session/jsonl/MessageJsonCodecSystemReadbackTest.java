package com.pijava.agent.session.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

/**
 * <b>包 B87a</b>（{@code docs/50 §4.2}）：系统消息的**回读**。
 *
 * <p>B87① 的症状是一对不对称：{@code SessionJson.messageNode} 写得出的系统消息，
 * {@code MessageJsonCodec.decode} 读不回来 —— {@code role: "system"} 落进
 * {@code default → "has unknown message role"}。同一处 codec 也覆盖
 * {@code retainedTail}（{@code EntryJsonCodec.java:47}）与 {@code originalPrompt}
 * （{@code RecordJsonCodec.java:96}），所以它同时是三个入口的回读。</p>
 *
 * <p>形状的对错由**写侧**钉住（{@code SessionJsonSystemMessageTest} 已断言 pi 的
 * 键集），本类钉的是**读回来的值**：字段不丢、sections 保序、两种 {@code toolsAdded}
 * 形状都认（pi 三键 ＋ A1 的七键旧形）、pi 的「{@code null} = 删除具名段」在 java
 * 的形状里表达不了 ⇒ **响亮**（{@code docs/50 §9 R1}）。</p>
 */
class MessageJsonCodecSystemReadbackTest {

    // ── round-trip：写侧能写的，读侧都要读回 ────────────────────────────

    @Test
    void roundTripsAFullSystemMessage() {
        var system = new Message.SystemMessage("base prompt", Instant.ofEpochMilli(7),
            Map.of("intro", "Base"),
            List.of(new ToolDefinition("read", "Read files", Map.of("type", "object"))),
            List.of(new ToolReference("write")));

        var decoded = MessageJsonCodec.decode(SessionJson.messageNode(system));

        assertThat(decoded).isInstanceOf(Message.SystemMessage.class);
        var back = (Message.SystemMessage) decoded;
        assertThat(back.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) back.content().get(0)).text()).isEqualTo("base prompt");
        assertThat(back.timestamp()).isEqualTo(Instant.ofEpochMilli(7));
        assertThat(back.sections()).containsExactly(Map.entry("intro", "Base"));
        assertThat(back.toolsAdded()).hasSize(1);
        assertThat(back.toolsAdded().get(0).name()).isEqualTo("read");
        assertThat(back.toolsAdded().get(0).description()).isEqualTo("Read files");
        assertThat(back.toolsAdded().get(0).inputSchema()).containsEntry("type", "object");
        assertThat(back.toolsRemoved()).extracting(ToolReference::name).containsExactly("write");
    }

    /**
     * sections 的渲染序是**插入序**（pi 的 {@code Object.entries}，{@code utils/text.ts:17}）——
     * 写侧已改成保序副本（包 A2 的 F3），读侧必须跟着保序，否则 round-trip 会静默重排
     * prompt 的段落。
     */
    @Test
    void roundTripPreservesSectionOrder() {
        var sections = new java.util.LinkedHashMap<String, String>();
        sections.put("first", "A");
        sections.put("second", "B");
        sections.put("third", "C");
        var system = new Message.SystemMessage("p", Instant.EPOCH, sections, List.of(), List.of());

        var back = (Message.SystemMessage) MessageJsonCodec.decode(SessionJson.messageNode(system));

        assertThat(back.sections().keySet()).containsExactly("first", "second", "third");
    }

    /** 三个可选字段全缺席（{@code SessionJson} 的空值省略门产出）⇒ 空集合 + null 时间戳。 */
    @Test
    void readsAMessageWithNoOptionalFields() {
        var system = new Message.SystemMessage(
            (List<ContentBlock>) null, null, null, null, null);

        var back = (Message.SystemMessage) MessageJsonCodec.decode(SessionJson.messageNode(system));

        assertThat(back.content()).isEmpty();
        assertThat(back.timestamp()).isNull();
        assertThat(back.sections()).isEmpty();
        assertThat(back.toolsAdded()).isEmpty();
        assertThat(back.toolsRemoved()).isEmpty();
    }

    // ── toolsAdded 的两种形状 ──────────────────────────────────────────

    /**
     * pi 的形状（{@code types.ts:600-605} 的 ai 层 {@code Tool}）：三个键
     * {@code {name, description, parameters}} —— 包 A2 的 {@code PiMessagesApi} 与
     * 包 B87b 之后的 {@code SessionJson} 都写这个。
     */
    @Test
    void readsPiShapedToolDeclarations() throws Exception {
        var node = SessionJson.mapper().readTree("""
            {"role":"system","content":[{"type":"text","text":"p"}],"timestamp":0,
             "toolsAdded":[{"name":"read","description":"Read files",
                            "parameters":{"type":"object","properties":{}}}]}
            """);

        var back = (Message.SystemMessage) MessageJsonCodec.decode(node);

        assertThat(back.toolsAdded()).hasSize(1);
        assertThat(back.toolsAdded().get(0).name()).isEqualTo("read");
        assertThat(back.toolsAdded().get(0).description()).isEqualTo("Read files");
        assertThat(back.toolsAdded().get(0).inputSchema()).containsEntry("type", "object");
    }

    /**
     * A1 的七键旧形（{@code valueToTree(ToolDefinition)}，schema 键名是 {@code inputSchema}）
     * —— 已经写在盘上的会话带的是这个形状，读侧**必须**兼容（写侧只写 pi 形状：读侧两种、
     * 写侧一种，是刻意的）。
     */
    @Test
    void readsLegacyToolDefinitionsWithInputSchema() throws Exception {
        var node = SessionJson.mapper().readTree("""
            {"role":"system","content":[{"type":"text","text":"p"}],"timestamp":0,
             "toolsAdded":[{"name":"read","description":"Read files",
                            "inputSchema":{"type":"object"},
                            "label":"Read","promptSnippet":"snippet",
                            "promptGuidelines":["g1"],"renderShell":"self"}]}
            """);

        var back = (Message.SystemMessage) MessageJsonCodec.decode(node);

        assertThat(back.toolsAdded()).hasSize(1);
        assertThat(back.toolsAdded().get(0).inputSchema()).containsEntry("type", "object");
        // pi 的 toolsAdded 是 ai 层 Tool，不带 A1 的四个元数据键（docs/50 §10 L-F）：
        // label 回落到 name、guidelines 为空、renderShell 回到缺省。
        assertThat(back.toolsAdded().get(0).label()).isEqualTo("read");
        assertThat(back.toolsAdded().get(0).promptGuidelines()).isEmpty();
        assertThat(back.toolsAdded().get(0).renderShell()).isEqualTo("default");
    }

    /** 两种 schema 键名都在场 ⇒ 取 pi 的 {@code parameters}（写侧的真相）。 */
    @Test
    void prefersParametersOverLegacyInputSchema() throws Exception {
        var node = SessionJson.mapper().readTree("""
            {"role":"system","content":[{"type":"text","text":"p"}],"timestamp":0,
             "toolsAdded":[{"name":"read","parameters":{"type":"pi"},
                            "inputSchema":{"type":"legacy"}}]}
            """);

        var back = (Message.SystemMessage) MessageJsonCodec.decode(node);

        assertThat(back.toolsAdded().get(0).inputSchema()).containsEntry("type", "pi");
    }

    // ── 响亮失败 ───────────────────────────────────────────────────────

    /**
     * pi 用 {@code null} 表达「删掉具名段」（{@code types.ts:501}），java 的
     * {@code Map<String,String>} 没有这个状态。静默丢键会**静默改变 prompt**，读成
     * 空串会把「删除」读成「清空」—— 两者都比抛错糟（{@code docs/50 §9 R1}）。
     */
    @Test
    void rejectsNullSectionValueInsteadOfDroppingTheKey() throws Exception {
        var node = SessionJson.mapper().readTree("""
            {"role":"system","content":[{"type":"text","text":"p"}],"timestamp":0,
             "sections":{"intro":"Base","obsolete":null}}
            """);

        assertThatThrownBy(() -> MessageJsonCodec.decode(node))
            .isInstanceOf(JsonlCodec.DecodeError.class)
            .hasMessageContaining("section");
    }

    /**
     * 形状错（声明缺 schema 键／字段不是数组／移除项缺 name）走既有 schema 错口径。
     *
     * <p>⚠️ 每条都断言**消息里点名了出问题的字段** —— 只断言
     * {@code isInstanceOf(DecodeError.class)} 的话，这些用例在缺陷态**恒真**
     * （修复前抛的也是 DecodeError，只是文案是 {@code has unknown message role}），
     * 那就是一组没牙的断言（{@code docs/45 §10} 的 B84 同型教训）。</p>
     */
    @Test
    void rejectsMalformedSystemFields() throws Exception {
        // 声明里两个 schema 键名都没有
        assertThatThrownBy(() -> MessageJsonCodec.decode(SessionJson.mapper().readTree(
            "{\"role\":\"system\",\"content\":[],\"toolsAdded\":[{\"name\":\"read\"}]}")))
            .isInstanceOf(JsonlCodec.DecodeError.class)
            .hasMessageContaining("toolsAdded");

        // 声明不是数组
        assertThatThrownBy(() -> MessageJsonCodec.decode(SessionJson.mapper().readTree(
            "{\"role\":\"system\",\"content\":[],\"toolsAdded\":{}}")))
            .isInstanceOf(JsonlCodec.DecodeError.class)
            .hasMessageContaining("toolsAdded");

        assertThatThrownBy(() -> MessageJsonCodec.decode(SessionJson.mapper().readTree(
            "{\"role\":\"system\",\"content\":[],\"sections\":\"nope\"}")))
            .isInstanceOf(JsonlCodec.DecodeError.class)
            .hasMessageContaining("sections");

        // 移除项缺 name（`requireString` 的既有文案）
        assertThatThrownBy(() -> MessageJsonCodec.decode(SessionJson.mapper().readTree(
            "{\"role\":\"system\",\"content\":[],\"toolsRemoved\":[{}]}")))
            .isInstanceOf(JsonlCodec.DecodeError.class)
            .hasMessageContaining("name");

        assertThatThrownBy(() -> MessageJsonCodec.decode(SessionJson.mapper().readTree(
            "{\"role\":\"system\",\"content\":[],\"toolsRemoved\":\"nope\"}")))
            .isInstanceOf(JsonlCodec.DecodeError.class)
            .hasMessageContaining("toolsRemoved");
    }

    /** 既有三种消息的回读一字不动（B87a 是纯增量）。 */
    @Test
    void legacyRolesStillDecode() {
        var user = MessageJsonCodec.decode(SessionJson.messageNode(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))));
        assertThat(user).isInstanceOf(Message.UserMessage.class);

        var tool = MessageJsonCodec.decode(SessionJson.messageNode(
            new Message.ToolResultMessage("call-1", "bash",
                List.of(new ContentBlock.TextContent("ok")), false)));
        assertThat(tool).isInstanceOf(Message.ToolResultMessage.class);
        assertThat(((Message.ToolResultMessage) tool).toolUseId()).isEqualTo("call-1");
    }
}
