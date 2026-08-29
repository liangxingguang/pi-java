package com.pijava.web;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件翻译层单测（Phase 7 设计 §4）：pi-java {@link AgentSessionEvent} →
 * pi-webui 前端 {@code agentEvent} 词汇。
 */
class AgentEventTranslatorTest {

    private final AgentEventTranslator translator = new AgentEventTranslator();

    private static AssistantMessage partial(String text) {
        return new AssistantMessage("id-1", List.of(new ContentBlock.TextContent(text)), null, null);
    }

    private static String type(WebServerMessage msg) {
        return ((WebServerMessage.AgentEvent) msg).event().get("type").asText();
    }

    @Test
    void startEmitsAgentStart() {
        var msgs = translator.translate(
            new AgentSessionEvent.MessageUpdate(new StreamEvent.Start(partial(""))));
        assertThat(msgs).singleElement().satisfies(m -> assertThat(type(m)).isEqualTo("agent_start"));
        assertThat(translator.isStreaming()).isTrue();
    }

    @Test
    void textDeltaEmitsMessageUpdate() {
        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.TextDelta(0, "hello", partial("hello"))));
        assertThat(msgs).hasSize(1);
        var ev = ((WebServerMessage.AgentEvent) msgs.get(0)).event();
        assertThat(type(msgs.get(0))).isEqualTo("message_update");
        assertThat(ev.get("message").get("role").asText()).isEqualTo("assistant");
        assertThat(ev.get("message").get("content").get(0).get("text").asText())
            .isEqualTo("hello");
    }

    @Test
    void toolCallEventsEmitToolExecution() {
        var start = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallStart(0, partial(""))));
        assertThat(type(start.get(0))).isEqualTo("tool_execution_start");
        var end = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallEnd(0, "call-1", "bash", java.util.Map.of(), partial(""))));
        assertThat(type(end.get(0))).isEqualTo("tool_execution_end");
    }

    @Test
    void streamErrorEmitsTopLevelError() {
        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.StreamError("error", new RuntimeException("boom"), partial(""))));
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0)).isInstanceOf(WebServerMessage.Error.class);
        assertThat(((WebServerMessage.Error) msgs.get(0)).message()).contains("boom");
        assertThat(translator.isStreaming()).isFalse();
    }

    @Test
    void agentEndEmitsAuthoritativeMessages() {
        var msgs = translator.translate(new AgentSessionEvent.AgentEnd(
            List.of(new Message.AssistantMessage(List.of(new ContentBlock.TextContent("done")))),
            false));
        assertThat(msgs).hasSize(1);
        var ev = ((WebServerMessage.AgentEvent) msgs.get(0)).event();
        assertThat(ev.get("type").asText()).isEqualTo("agent_end");
        assertThat(ev.get("willRetry").asBoolean()).isFalse();
        assertThat(ev.get("messages").size()).isEqualTo(1);
    }

    @Test
    void emptyAgentEndOmitsMessages() {
        var msgs = translator.translate(new AgentSessionEvent.AgentEnd(List.of(), true));
        var ev = ((WebServerMessage.AgentEvent) msgs.get(0)).event();
        assertThat(ev.get("messages")).isNull();
    }

    @Test
    void agentSettledEmitsTurnEnd() {
        var msgs = translator.translate(new AgentSessionEvent.AgentSettled());
        assertThat(type(msgs.get(0))).isEqualTo("turn_end");
        assertThat(translator.isStreaming()).isFalse();
    }

    @Test
    void agentEndConvertsMessagesToPiShape() {
        var msgs = translator.translate(new AgentSessionEvent.AgentEnd(
            List.of(new Message.ToolResultMessage("call-1", "bash",
                List.of(new ContentBlock.TextContent("out")), false)),
            false));
        var ev = ((WebServerMessage.AgentEvent) msgs.get(0)).event();
        var first = ev.get("messages").get(0);
        assertThat(first.get("role").asText()).isEqualTo("toolResult");
        assertThat(first.get("toolCallId").asText()).isEqualTo("call-1");
    }

    @Test
    void messageUpdateThinkingUsesThinkingField() {
        var partial = new AssistantMessage("id-1",
            List.of(new ContentBlock.ThinkingContent("thought")), null, null);
        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ThinkingDelta(0, "thought", partial)));
        var ev = ((WebServerMessage.AgentEvent) msgs.get(0)).event();
        assertThat(ev.get("message").get("content").get(0).get("thinking").asText())
            .isEqualTo("thought");
    }

    @Test
    void bashOutputEmitsBashOutputEvent() {
        var msgs = translator.translate(new AgentSessionEvent.BashExecutionUpdate("bash-1", "out"));
        var ev = ((WebServerMessage.AgentEvent) msgs.get(0)).event();
        assertThat(ev.get("type").asText()).isEqualTo("bashOutput");
        assertThat(ev.get("id").asText()).isEqualTo("bash-1");
        assertThat(ev.get("delta").asText()).isEqualTo("out");
    }
}
