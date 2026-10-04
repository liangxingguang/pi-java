package com.pijava.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

/**
 * A1（{@code docs/08} §A1）：{@code SessionJson.messageNode} 的第四种消息分支。
 *
 * <p>系统消息按 pi 的 {@code SystemMessage} 形状落库（{@code ai/src/types.ts:491-509}）：
 * {@code role}/{@code content} 走通用规则，另加 {@code timestamp}（epoch ms）与三个
 * <b>可选</b>字段 {@code sections}/{@code toolsAdded}/{@code toolsRemoved}。可选字段的
 * 缺席规则同 §8.18 的 A7：pi 的对象字面量展开是 {@code ...(x ? {x} : {})}，空值在线上
 * 没有这个键，而 Jackson 会把空 Map/List 照样写出来 —— 必须主动省略。</p>
 *
 * <p>⚠️ <b>A1 只做落线，不做回读</b>：{@code MessageJsonCodec.decode} 对
 * {@code role: "system"} 仍然抛 {@code unknown message role}（设计文档 §JSON shape 明写
 * 「does not add a generic deserializer for {@code Message}」）。所以本类只断言节点形状，
 * 不写 round-trip —— 回读是后续设计包的活。</p>
 */
class SessionJsonSystemMessageTest {

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void systemMessageNodeWritesRoleContentAndTimestamp() {
        var system = new Message.SystemMessage(
            "base prompt", Instant.ofEpochMilli(7), Map.of(), List.of(), List.of());

        var node = SessionJson.messageNode(system);

        assertThat(node.get("role").asText()).isEqualTo("system");
        assertThat(node.get("content")).hasSize(1);
        assertThat(node.get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(node.get("content").get(0).get("text").asText()).isEqualTo("base prompt");
        assertThat(node.get("timestamp").asLong()).isEqualTo(7L);
        assertThat(fieldNames(node))
            .as("空的 sections/toolsAdded/toolsRemoved 必须在线上缺席")
            .containsExactlyInAnyOrder("role", "content", "timestamp");
    }

    @Test
    void systemMessageNodeWritesNonEmptyOptionalFieldsUnderPiNames() {
        var system = new Message.SystemMessage("base", Instant.ofEpochMilli(3),
            Map.of("intro", "Base"),
            List.of(new ToolDefinition("read", "Read files", Map.of("type", "object"))),
            List.of(new ToolReference("write")));

        var node = SessionJson.messageNode(system);

        assertThat(node.get("sections").get("intro").asText()).isEqualTo("Base");
        assertThat(node.get("toolsAdded")).hasSize(1);
        assertThat(node.get("toolsAdded").get(0).get("name").asText()).isEqualTo("read");
        assertThat(node.get("toolsRemoved")).hasSize(1);
        assertThat(node.get("toolsRemoved").get(0).get("name").asText()).isEqualTo("write");
        assertThat(fieldNames(node)).containsExactlyInAnyOrder(
            "role", "content", "timestamp", "sections", "toolsAdded", "toolsRemoved");
    }

    /**
     * 包 B87b（{@code 原 docs/50 §4.3}）：{@code toolsAdded} 的**线格形状**是 pi 的 ai 层
     * {@code Tool} —— 三个键 {@code {name, description, parameters}}（{@code types.ts:600-605}），
     * 不是 {@code ToolDefinition} 全形（7 组件、schema 键名 {@code inputSchema}）。
     *
     * <p>收敛之前这里是 Jackson 对 record 的默认序列化；同仓的 {@code PiMessagesApi}
     * 早已在写 pi 形状（它自己的 javadoc 就点名了这对并存）。两处现在共用
     * {@code Transcripts.toToolDeclaration}。</p>
     */
    @Test
    void toolsAddedIsWrittenInPiToolShape() {
        var system = new Message.SystemMessage("base", Instant.ofEpochMilli(3), Map.of(),
            List.of(new ToolDefinition("read", "Read files", Map.of("type", "object"))),
            List.of());

        var node = SessionJson.messageNode(system);

        var tool = node.get("toolsAdded").get(0);
        assertThat(fieldNames(tool)).as("pi 的 ai 层 Tool 只有这三个键")
            .containsExactlyInAnyOrder("name", "description", "parameters");
        assertThat(tool.get("parameters").get("type").asText()).isEqualTo("object");
    }

    /**
     * 时间戳缺席 ⇒ 键省略。pi 的 {@code timestamp} 是必填 number，Java 的
     * {@code Instant} 可以为 null（只有手写构造才会走到）—— 写 {@code null} 会造出
     * pi 里不存在的线格形状，省略才是「没有时间戳」的 Java 侧等价物（同 A7 规则）。
     */
    @Test
    void systemMessageNodeOmitsAbsentTimestamp() {
        var system = new Message.SystemMessage(
            (List<ContentBlock>) null, null, null, null, null);

        var node = SessionJson.messageNode(system);

        assertThat(fieldNames(node)).containsExactlyInAnyOrder("role", "content");
    }

    /**
     * 包 A4a：{@code null} 值的段**必须留在线上**（pi 的 {@code Record<string,string|null>}，
     * {@code types.ts:501}）。
     *
     * <p>⚠️ 这是个真陷阱：本类用的 {@code SessionJson.mapper()} 带
     * {@code setSerializationInclusion(NON_NULL)}，**{@code valueToTree} 会把 null 值的条目
     * 整个丢掉** —— 于是「删掉 {@code obsolete} 段」静默变成「没提过 {@code obsolete}」，
     * 而两者的重放结果**完全不同**（前者删段，后者保留此前设过的值）。</p>
     */
    @Test
    void systemMessageNodeKeepsRemovalSectionsAsExplicitNulls() {
        var sections = new LinkedHashMap<String, String>();
        sections.put("intro", "Base");
        sections.put("obsolete", null);
        var system = new Message.SystemMessage("base", Instant.ofEpochMilli(3), sections,
            List.of(), List.of());

        var node = SessionJson.messageNode(system);

        assertThat(node.get("sections").has("obsolete"))
            .as("删除项被 NON_NULL 丢掉 ⇒ 静默把「删除」读成「不提」")
            .isTrue();
        assertThat(node.get("sections").get("obsolete").isNull()).isTrue();
        assertThat(node.get("sections").get("intro").asText()).isEqualTo("Base");
    }

    /** 既有三种消息的节点形状必须一字不动（A1 是纯增量）。 */
    @Test
    void legacyMessageNodesKeepTheirExistingKeys() {
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        assertThat(fieldNames(SessionJson.messageNode(user)))
            .containsExactly("role", "content");

        var tool = new Message.ToolResultMessage("call-1", "bash",
            List.of(new ContentBlock.TextContent("ok")), false);
        assertThat(fieldNames(SessionJson.messageNode(tool)))
            .containsExactly("role", "content", "toolUseId", "toolName", "isError");

        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("answer")), "stop", null,
            "anthropic-messages", "anthropic", "claude-sonnet-5",
            com.pijava.ai.Usage.of(1, 2), Instant.ofEpochMilli(5), null, null);
        assertThat(fieldNames(SessionJson.messageNode(assistant)))
            .containsExactly("role", "content", "stopReason", "api", "provider", "model",
                "usage", "timestamp");
    }
}
