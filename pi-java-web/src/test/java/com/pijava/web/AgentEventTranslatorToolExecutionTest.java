package com.pijava.web;

import java.util.List;

import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑦（docs/34）：web 面的 {@code tool_execution_*} **换源**。
 *
 * <p>此前它们由 {@code StreamEvent.ToolCallStart/Delta/End} 伪造 ——
 * 那是「模型把这次调用**吐完**了」的时刻（pi 的 {@code toolcall_*} 增量，
 * 对应 pi 的事件是 {@code message_update} 里嵌套的 {@code assistantMessageEvent}），
 * <b>不是</b>工具执行的生命周期。pi 的 {@code tool_execution_start} 在工具**即将
 * 执行**时发（校验与 {@code beforeToolCall} 钩子之前），{@code end} 在执行并定稿
 * 之后发。⇒ 工具跑 30 秒，前端这 30 秒里此前收不到任何东西。</p>
 *
 * <p>本包把源换成真正的 {@link AgentSessionEvent.ToolExecutionStart}／
 * {@link AgentSessionEvent.ToolExecutionUpdate}／{@link AgentSessionEvent.ToolExecutionEnd}，
 * 并**删掉**从流事件伪造的那三条（载荷按裁决 A 照 pi 的透传带上）。</p>
 */
class AgentEventTranslatorToolExecutionTest {

    private final AgentEventTranslator translator = new AgentEventTranslator();

    private static com.fasterxml.jackson.databind.JsonNode event(WebServerMessage msg) {
        return ((WebServerMessage.AgentEvent) msg).event();
    }

    private static List<String> types(List<WebServerMessage> msgs) {
        return msgs.stream()
            .map(m -> event(m).get("type").asText())
            .toList();
    }

    @Test
    void toolExecutionStartIsPushedWithItsPayload() {
        var msgs = translator.translate(new AgentSessionEvent.ToolExecutionStart(
            "call_1", "write", java.util.Map.of("path", "a.txt")));

        assertThat(msgs).hasSize(1);
        var ev = event(msgs.get(0));
        assertThat(ev.get("type").asText()).isEqualTo("tool_execution_start");
        assertThat(ev.get("toolCallId").asText()).isEqualTo("call_1");
        assertThat(ev.get("toolName").asText()).isEqualTo("write");
        assertThat(ev.get("args").get("path").asText()).isEqualTo("a.txt");
    }

    @Test
    void toolExecutionUpdateIsPushedWithItsPayload() {
        var msgs = translator.translate(new AgentSessionEvent.ToolExecutionUpdate(
            "call_1", "bash", java.util.Map.of("command", "ls"),
            ToolResult.success("partial")));

        assertThat(types(msgs)).containsExactly("tool_execution_update");
        var ev = event(msgs.get(0));
        assertThat(ev.get("partialResult").get("content").get(0).get("text").asText())
            .isEqualTo("partial");
    }

    @Test
    void toolExecutionEndIsPushedWithResultAndIsError() {
        var msgs = translator.translate(new AgentSessionEvent.ToolExecutionEnd(
            "call_1", "write", ToolResult.success("ok"), false));

        assertThat(types(msgs)).containsExactly("tool_execution_end");
        var ev = event(msgs.get(0));
        assertThat(ev.get("result").get("content").get(0).get("text").asText())
            .isEqualTo("ok");
        assertThat(ev.get("isError").asBoolean()).isFalse();
    }

    @Test
    void resultPayloadOmitsAbsentOptionalFields() {
        // 与 RPC 线同一条投影（裁决 B）—— 否则会多出 pi 没有的三个键。
        var msgs = translator.translate(new AgentSessionEvent.ToolExecutionEnd(
            "call_1", "write", ToolResult.success("ok"), false));
        var result = event(msgs.get(0)).get("result");

        assertThat(result.has("terminate")).isFalse();
        assertThat(result.has("addedToolNames")).isFalse();
        assertThat(result.has("details")).isFalse();
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    void streamToolCallEventsNoLongerFabricateToolExecutionFrames() {
        // ⑥ 钉住「换源」：流式的 toolcall_* 增量**不再**产生 tool_execution_*。
        // 它仍然产生 message_update（包⑥ 的产物，那个是正确的出口）。
        var partial = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("call_1", "write", java.util.Map.of())));

        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallStart(0, partial)));

        assertThat(types(msgs)).as("只剩 message_update").containsExactly("message_update");
        assertThat(event(msgs.get(0)).get("message").get("content").get(0).get("type").asText())
            .isEqualTo("toolCall");
    }

    @Test
    void streamToolCallDeltaAndEndDoNotFabricateEither() {
        var partial = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("call_1", "write", java.util.Map.of("p", 1))));

        assertThat(types(translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallDelta(0, "call_1", "{}", partial)))))
            .containsExactly("message_update");
        assertThat(types(translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallEnd(0, "call_1", "write", java.util.Map.of(), partial)))))
            .containsExactly("message_update");
    }
}
