package com.pijava.coding.agent.mode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 包⑩（docs/37）：RPC 线的消息形状。
 *
 * <p><b>头条是「会抛」，不是「少字段」</b>：{@code JsonEventMapper} 用**裸**
 * {@code ObjectMapper} ＋ 两个 mixin、**未注册 jsr310**，而
 * {@code AbstractChatApi:73} 给每条消息挂 {@code Instant.now()} ⇒ {@code agent_end} 的
 * {@code valueToTree} 抛 {@code IllegalArgumentException: Instant not supported}。
 * 按包⑦ 已核的隔离性（{@code SessionEventHub} 逐个 listener
 * {@code catch (RuntimeException)}）⇒ <b>丢该客户端整帧</b>
 * ⇒ <b>生产上的 RPC 客户端收不到 {@code agent_end}</b>。</p>
 *
 * <p>⚠️ 这一条能藏这么久的结构性原因：{@code FauxProvider} 自带的
 * {@code FauxChatApi} <b>直接实现 {@code ChatApi}、绕过 {@code AbstractChatApi}</b>
 * ⇒ 既有夹具里的消息 timestamp 恒 null、永不触发（台账 A16）。
 * 故本夹具<b>手工构造带 {@code Instant} 的消息</b> —— 如实标注：它钉得住
 * 「mapper 会不会抛」，但钉不住「生产会不会走到这里」（那是 A16 的事）。</p>
 */
class RpcWireMessageShapeTest {

    private static Message.AssistantMessage timestampedAssistant() {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hello")),
            "stop", null, "anthropic", "anthropic", "m", Usage.of(1, 2),
            Instant.ofEpochMilli(1_700_000_000_000L), null, null);
    }

    // ── ① 止血：带 timestamp 的消息不再抛 ────────────────────────────

    @Test
    void messageWithTimestampDoesNotThrow() {
        var event = new AgentSessionEvent.AgentEnd(List.of(timestampedAssistant()), false);

        assertThatCode(() -> JsonEventMapper.toWire(event))
            .as("生产上的消息带 Instant.now() ⇒ 不能抛（抛 = 丢整帧）")
            .doesNotThrowAnyException();
    }

    @Test
    void timestampIsWrittenAsEpochMilliseconds() {
        // pi 的 `timestamp: number` 是 Unix 毫秒（ai/src/types.ts 三个 interface 都声明）。
        var node = JsonEventMapper.toWire(
            new AgentSessionEvent.AgentEnd(List.of(timestampedAssistant()), false));

        assertThat(node.get("messages").get(0).get("timestamp").asLong())
            .isEqualTo(1_700_000_000_000L);
    }

    // ── ②③④ role 判别值 ────────────────────────────────────────────

    @Test
    void everyMessageCarriesItsRoleLiteral() {
        var messages = List.<Message>of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
            timestampedAssistant(),
            new Message.ToolResultMessage("c1", "bash",
                List.of(new ContentBlock.TextContent("out")), Map.of(),
                null, List.of(), false));

        var node = JsonEventMapper.toWire(new AgentSessionEvent.AgentEnd(messages, false));

        assertThat(node.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(node.get("messages").get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(node.get("messages").get(2).get("role").asText()).isEqualTo("toolResult");
    }

    @Test
    void toolResultUsesPiFieldNameToolCallId() {
        var result = new Message.ToolResultMessage("c1", "bash",
            List.of(new ContentBlock.TextContent("out")), Map.of(),
            null, List.of(), false);

        var node = JsonEventMapper.toWire(
            new AgentSessionEvent.AgentEnd(List.<Message>of(result), false));

        var m = node.get("messages").get(0);
        assertThat(m.get("toolCallId").asText()).as("pi 叫 toolCallId").isEqualTo("c1");
        assertThat(m.has("toolUseId")).as("record 组件名不上线").isFalse();
    }

    // ── ⑤ 内容块的判别字面量 ────────────────────────────────────────

    @Test
    void contentBlockDiscriminatorsMatchPi() {
        var assistant = new Message.AssistantMessage(List.of(
            new ContentBlock.TextContent("t"),
            new ContentBlock.ThinkingContent("why"),
            new ContentBlock.ToolUseContent("c1", "bash", Map.of("cmd", "ls"))),
            "tool_use", null, "anthropic", "anthropic", "m", Usage.of(1, 2), null, null, null);

        var content = JsonEventMapper.toWire(
            new AgentSessionEvent.AgentEnd(List.<Message>of(assistant), false))
            .get("messages").get(0).get("content");

        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
        assertThat(content.get(1).get("type").asText())
            .as("pi 的判别值是 thinking").isEqualTo("thinking");
        assertThat(content.get(1).get("thinking").asText())
            .as("pi 的字段名是 thinking（不是 text）").isEqualTo("why");
        assertThat(content.get(2).get("type").asText())
            .as("pi 的判别值是 toolCall（不是 tool_use）").isEqualTo("toolCall");
        assertThat(content.get(2).get("name").asText()).isEqualTo("bash");
        assertThat(content.get(2).get("arguments").get("cmd").asText()).isEqualTo("ls");
    }

    // ── ⑥ 可选键按 pi 的 `?` 缺席即省略 ─────────────────────────────

    @Test
    void absentOptionalKeysAreOmittedNotWrittenEmpty() {
        var result = new Message.ToolResultMessage("c1", "bash",
            List.of(new ContentBlock.TextContent("out")), Map.of(),
            null, List.of(), false);
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.ThinkingContent("why")),
            "stop", null, "anthropic", "anthropic", "m", Usage.of(1, 2), null, null, null);

        var node = JsonEventMapper.toWire(
            new AgentSessionEvent.AgentEnd(List.<Message>of(result, assistant), false));

        assertThat(node.get("messages").get(0).has("addedToolNames"))
            .as("pi 的 addedToolNames? 缺席即省略").isFalse();
        var thinking = node.get("messages").get(1).get("content").get(0);
        assertThat(thinking.has("signature"))
            .as("pi 的 signature? 缺席即省略").isFalse();
        assertThat(thinking.has("redacted"))
            .as("pi 的 redacted? 缺席即省略（false 也算缺席）").isFalse();
    }
}
