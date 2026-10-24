package com.pijava.coding.agent.mode;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 20（E7-4 核销，T4）：RPC 事件线格式 parity——pi {@code modes/json-event.ts}
 * （全 61 行）三面：① {@code message_update} 拍平（顶层 usage＋增量剥 partial）；
 * ② {@code toolcall_start} 补 id/toolName＋错位即抛（错文逐字）；③ 其余事件
 * 原样透传。B27–B29（包④ RPC 重试面）已闭——本类钉线格不漂。
 */
class JsonEventParityTest {

    private static AssistantMessage partialWith(ContentBlock block) {
        return AssistantMessage.empty().withContent(List.of(block));
    }

    @Test
    void messageUpdateIsFlattenedWithTopLevelUsageAndDeltaOnly() {
        var partial = partialWith(new ContentBlock.TextContent("hel"));
        var event = new AgentSessionEvent.MessageUpdate(
            new StreamEvent.TextDelta(0, "hel", partial));

        var node = JsonEventMapper.toWire(event);

        // pi json-event.ts:56-60 逐字形：{type, usage, assistantMessageEvent}。
        assertThat(node.get("type").asText()).isEqualTo("message_update");
        assertThat(node.has("usage")).as("pi 恒写顶层 usage").isTrue();
        var delta = node.get("assistantMessageEvent");
        assertThat(delta.get("type").asText()).isEqualTo("text_delta");
        assertThat(delta.get("contentIndex").asInt()).isEqualTo(0);
        assertThat(delta.get("delta").asText()).isEqualTo("hel");
        assertThat(delta.has("partial"))
            .as("累积快照剥除（json-event.ts:36-44 注释语义）").isFalse();
    }

    @Test
    void toolcallStartGetsIdAndToolNameFromPartialContent() {
        var partial = partialWith(
            new ContentBlock.ToolUseContent("call-1", "bash", Map.of()));
        var event = new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallStart(0, partial));

        var delta = JsonEventMapper.toWire(event).get("assistantMessageEvent");

        // pi json-event.ts:22-29：{...deltaEvent, id, toolName}，partial 剥除。
        assertThat(delta.get("type").asText()).isEqualTo("toolcall_start");
        assertThat(delta.get("id").asText()).isEqualTo("call-1");
        assertThat(delta.get("toolName").asText()).isEqualTo("bash");
        assertThat(delta.has("partial")).isFalse();
    }

    @Test
    void toolcallStartWithMisalignedContentThrowsVerbatim() {
        var partial = partialWith(new ContentBlock.TextContent("not a tool call"));
        var event = new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallStart(0, partial));

        // pi json-event.ts:24-27 错文逐字——越界/非 toolCall 同抛（不变量断言）。
        assertThatThrownBy(() -> JsonEventMapper.toWire(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("toolcall_start content at index 0 is not a tool call");

        var outOfBounds = new AgentSessionEvent.MessageUpdate(
            new StreamEvent.ToolCallStart(5, partial));
        assertThatThrownBy(() -> JsonEventMapper.toWire(outOfBounds))
            .hasMessage("toolcall_start content at index 5 is not a tool call");
    }

    @Test
    void nonMessageUpdateEventsPassThroughWithPiTypeNames() {
        // pi json-event.ts:48-51：非 message_update 事件原样透传（type 名逐字）。
        assertThat(JsonEventMapper.toWire(new AgentSessionEvent.AgentStart())
            .get("type").asText()).isEqualTo("agent_start");
        assertThat(JsonEventMapper.toWire(new AgentSessionEvent.AgentSettled(false))
            .get("type").asText()).isEqualTo("agent_settled");
        var queue = JsonEventMapper.toWire(
            new AgentSessionEvent.QueueUpdate(List.of("s"), List.of("f")));
        assertThat(queue.get("type").asText()).isEqualTo("queue_update");
        assertThat(queue.get("steering").get(0).asText()).isEqualTo("s");
        assertThat(queue.get("followUp").get(0).asText()).isEqualTo("f");
    }

    @Test
    void terminalVariantsUseExplicitProjectionWithoutPartial() {
        // done 终局（StreamDone）也不带 partial 上增量线。
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("final")))
            .withStopReason(StopReason.STOP);
        var event = new AgentSessionEvent.MessageUpdate(
            new StreamEvent.StreamDone(StopReason.STOP, null, done));

        var node = JsonEventMapper.toWire(event);
        assertThat(node.get("assistantMessageEvent").get("type").asText())
            .isEqualTo("done");
        assertThat(node.get("assistantMessageEvent").has("partial")).isFalse();
    }
}
