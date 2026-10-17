package com.pijava.web;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件翻译层单测（Phase 7 设计 §4）：pi-java {@link AgentSessionEvent} →
 * pi-webui 前端 {@code agentEvent} 词汇。
 *
 * <p>批 2 直译化：会话事件面已是 pi 的单一漏斗 ⇒ 本层逐帧透传；{@code agent_end}
 * 的 messages 数组由构造注入的<b>全量转录源</b>供（会话面载荷自 #52 起是本 pass
 * 新增），{@code agent_settled} 只收 streaming、不发帧。</p>
 */
class AgentEventTranslatorTest {

    /** 全量转录源的后备存储（agent_end 帧的 messages 数组由它供）。 */
    private final List<Message> transcript = new ArrayList<>();
    private final AgentEventTranslator translator = new AgentEventTranslator(() -> transcript);

    private static AssistantMessage partial(String text) {
        return new AssistantMessage("id-1", List.of(new ContentBlock.TextContent(text)), null, null);
    }

    private static String type(WebServerMessage msg) {
        return ((WebServerMessage.AgentEvent) msg).event().get("type").asText();
    }

    private static com.fasterxml.jackson.databind.JsonNode event(WebServerMessage msg) {
        return ((WebServerMessage.AgentEvent) msg).event();
    }

    // ── 批 2 新真源：会话面五帧逐字透传 ─────────────────────────────────

    @Test
    void agentStartEventEmitsAgentStartAndOpensStreaming() {
        var msgs = translator.translate(new AgentSessionEvent.AgentStart());
        assertThat(msgs).singleElement().satisfies(m -> assertThat(type(m)).isEqualTo("agent_start"));
        assertThat(translator.isStreaming()).isTrue();
    }

    @Test
    void streamStartNoLongerSynthesizesAgentStart() {
        // 会话面的 AgentStart 恒先到（引擎在流开始之前发射）⇒ 合成支已删（防双发）。
        var msgs = translator.translate(
            new AgentSessionEvent.MessageUpdate(new StreamEvent.Start(partial(""))));
        assertThat(msgs).isEmpty();
    }

    @Test
    void turnStartPassesThrough() {
        var msgs = translator.translate(new AgentSessionEvent.TurnStart());
        assertThat(msgs).singleElement().satisfies(m -> assertThat(type(m)).isEqualTo("turn_start"));
    }

    @Test
    void messageStartPassesThroughForAllRoles() {
        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        var msgs = translator.translate(new AgentSessionEvent.MessageStart(user));
        assertThat(msgs).hasSize(1);
        var ev = event(msgs.get(0));
        assertThat(ev.get("type").asText()).isEqualTo("message_start");
        assertThat(ev.get("message").get("role").asText()).isEqualTo("user");
    }

    @Test
    void messageEndCarriesTheUserEcho() {
        // main.ts:327-339 的 user 回显路径：run 启动即上列表，不等 agent_end 整表替换。
        var msgs = translator.translate(new AgentSessionEvent.MessageEnd(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("what is the weather?")))));
        assertThat(msgs).hasSize(1);
        var ev = event(msgs.get(0));
        assertThat(ev.get("type").asText()).isEqualTo("message_end");
        assertThat(ev.get("message").get("role").asText()).isEqualTo("user");
        assertThat(ev.get("message").get("content").get(0).get("text").asText())
            .isEqualTo("what is the weather?");
    }

    // ── StreamEvent 增量 ────────────────────────────────────────────────

    @Test
    void textDeltaEmitsMessageUpdate() {
        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.TextDelta(0, "hello", partial("hello"))));
        assertThat(msgs).hasSize(1);
        var ev = event(msgs.get(0));
        assertThat(type(msgs.get(0))).isEqualTo("message_update");
        assertThat(ev.get("message").get("role").asText()).isEqualTo("assistant");
        assertThat(ev.get("message").get("content").get(0).get("text").asText())
            .isEqualTo("hello");
    }

    // ⚠️ 包⑦（原 docs/34）**删除**了 `toolCallEventsEmitToolExecution`：它钉的是
    // 「StreamEvent.ToolCall* → tool_execution_*」这条**已被裁决删掉**的伪造路径。
    // 换源后的正反两面（真正的工具执行事件 → tool_execution_*；流式增量**不再**
    // 伪造）由 AgentEventTranslatorToolExecutionTest 覆盖。

    @Test
    void streamErrorEmitsTopLevelError() {
        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.StreamError(StopReason.ERROR, new RuntimeException("boom"), partial(""))));
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0)).isInstanceOf(WebServerMessage.Error.class);
        assertThat(((WebServerMessage.Error) msgs.get(0)).message()).contains("boom");
        assertThat(translator.isStreaming()).isFalse();
    }

    // ── agent_end：messages 由全量转录源供（#52 适配） ────────────────────

    @Test
    void agentEndMessagesComeFromTheTranscriptSource() {
        transcript.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("q"))));
        transcript.add(new Message.AssistantMessage(List.of(new ContentBlock.TextContent("done"))));
        // 事件载荷是本 pass 新增（甚至可能为空）——帧上的数组必须仍是全量转录。
        var msgs = translator.translate(new AgentSessionEvent.AgentEnd(List.of(), false));
        assertThat(msgs).hasSize(1);
        var ev = event(msgs.get(0));
        assertThat(ev.get("type").asText()).isEqualTo("agent_end");
        assertThat(ev.get("willRetry").asBoolean()).isFalse();
        assertThat(ev.get("messages")).hasSize(2);
        assertThat(translator.isStreaming()).isFalse();
    }

    @Test
    void emptyTranscriptOmitsMessagesOnAgentEnd() {
        var msgs = translator.translate(new AgentSessionEvent.AgentEnd(List.of(), true));
        var ev = event(msgs.get(0));
        assertThat(ev.get("messages")).isNull();
    }

    @Test
    void agentEndConvertsMessagesToPiShape() {
        transcript.add(new Message.ToolResultMessage("call-1", "bash",
            List.of(new ContentBlock.TextContent("out")), false));
        var msgs = translator.translate(new AgentSessionEvent.AgentEnd(List.of(), false));
        var ev = event(msgs.get(0));
        var first = ev.get("messages").get(0);
        assertThat(first.get("role").asText()).isEqualTo("toolResult");
        assertThat(first.get("toolCallId").asText()).isEqualTo("call-1");
    }

    // ── agent_settled：收 streaming，不发帧 ──────────────────────────────

    @Test
    void agentSettledClosesStreamingWithoutEmittingAFrame() {
        translator.translate(new AgentSessionEvent.AgentStart());
        var msgs = translator.translate(new AgentSessionEvent.AgentSettled(true));
        assertThat(msgs).as("main.ts 无 agent_settled 消费 ⇒ 不发帧").isEmpty();
        assertThat(translator.isStreaming()).isFalse();
    }

    // ── turn_end：每回合一条，两字段必填（types.ts:517） ───────────────────

    /**
     * pi 的 {@code turn_end} 是 {@code {message, toolResults}}，两个字段都必填
     * （{@code agent/types.ts:517}）。前端按 {@code toolCallId} 去重后把
     * {@code toolResults} 追加进消息列表（{@code client/main.ts:341-353}）。
     */
    @Test
    void turnEndCarriesTheToolResultsTheFrontendCollects() {
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("done")), StopReason.STOP, null,
            null, null, null, null, null, null, null);
        var result = new Message.ToolResultMessage("call-1", "bash",
            List.of(new ContentBlock.TextContent("out")), null, null, List.of(), false, null);

        var msgs = translator.translate(
            new AgentSessionEvent.TurnEnd(assistant, List.of(result)));

        var ev = event(msgs.get(0));
        assertThat(ev.get("type").asText()).isEqualTo("turn_end");
        assertThat(ev.get("message").get("role").asText()).isEqualTo("assistant");
        assertThat(ev.get("toolResults")).hasSize(1);
        // 前端按这个键去重 ⇒ web 线的名字必须是 pi 的 `toolCallId`
        // （WebWireJson 手工改名，与 RPC 线的 record 组件名 toolUseId 不同）。
        assertThat(ev.get("toolResults").get(0).get("toolCallId").asText())
            .isEqualTo("call-1");
        assertThat(ev.get("toolResults").get(0).get("role").asText())
            .isEqualTo("toolResult");
    }

    @Test
    void turnEndWithoutToolsStillCarriesAnEmptyArray() {
        // 反向：无工具回合同样是**空数组**（pi 恒发数组，不是省略键）；message 恒非 null。
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("done")), StopReason.STOP, null,
            null, null, null, null, null, null, null);
        var msgs = translator.translate(new AgentSessionEvent.TurnEnd(assistant, List.of()));

        var ev = event(msgs.get(0));
        assertThat(ev.get("toolResults").isArray()).isTrue();
        assertThat(ev.get("toolResults")).isEmpty();
        assertThat(ev.has("message")).as("turn_end.message 必填（失败路是合成失败消息）").isTrue();
    }

    // ── 其余不动支 ────────────────────────────────────────────────────────

    @Test
    void messageUpdateThinkingUsesThinkingField() {
        var partial = new AssistantMessage("id-1",
            List.of(new ContentBlock.ThinkingContent("thought")), null, null);
        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ThinkingDelta(0, "thought", partial)));
        var ev = event(msgs.get(0));
        assertThat(ev.get("message").get("content").get(0).get("thinking").asText())
            .isEqualTo("thought");
    }

    @Test
    void bashOutputEmitsBashOutputEvent() {
        var msgs = translator.translate(new AgentSessionEvent.BashExecutionUpdate("bash-1", "out"));
        var ev = event(msgs.get(0));
        assertThat(ev.get("type").asText()).isEqualTo("bashOutput");
        assertThat(ev.get("id").asText()).isEqualTo("bash-1");
        assertThat(ev.get("delta").asText()).isEqualTo("out");
    }
}
