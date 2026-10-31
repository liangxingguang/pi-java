package com.pijava.evals.harness;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §3.3）：{@link TranscriptEvents} 的事件映射与工具调用状态反推。
 */
class TranscriptEventsTest {

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.AssistantMessage assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks));
    }

    private static ContentBlock.ToolUseContent call(String id, String name,
                                                    Map<String, Object> args) {
        return new ContentBlock.ToolUseContent(id, name, args);
    }

    private static Message.ToolResultMessage result(String id, String name, String text,
                                                    boolean error) {
        return new Message.ToolResultMessage(id, name,
            List.of(new ContentBlock.TextContent(text)), error);
    }

    @Test
    void mapsMessagesCallsAndResultsInTranscriptOrder() {
        List<Message> messages = List.of(
            user("hello"),
            assistant(new ContentBlock.TextContent("checking"),
                call("call-1", "read", Map.of("path", "a.txt"))),
            result("call-1", "read", "data", false));

        var events = TranscriptEvents.from(messages);

        assertThat(events).hasSize(4);
        assertThat(events.get(0).type()).isEqualTo("message");
        assertThat(events.get(0).role()).isEqualTo("user");
        assertThat(events.get(0).content()).isEqualTo("hello");
        assertThat(events.get(1).type()).isEqualTo("message");
        assertThat(events.get(1).role()).isEqualTo("assistant");
        assertThat(events.get(1).content()).isEqualTo("checking");
        assertThat(events.get(2).type()).isEqualTo("tool_call");
        assertThat(events.get(2).id()).isEqualTo("call-1");
        assertThat(events.get(2).name()).isEqualTo("read");
        assertThat(events.get(2).arguments()).containsEntry("path", "a.txt");
        assertThat(events.get(3).type()).isEqualTo("tool_result");
        assertThat(events.get(3).toolCallId()).isEqualTo("call-1");
        assertThat(events.get(3).toolName()).isEqualTo("read");
        assertThat(events.get(3).content()).isEqualTo("data");
        assertThat(events.get(3).error()).isFalse();
    }

    @Test
    void skipsEmptyAssistantTextButKeepsToolCalls() {
        var events = TranscriptEvents.from(List.of(
            assistant(call("call-1", "read", Map.of()))));
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("tool_call");
    }

    @Test
    void derivesOkErrorAndPendingStatuses() {
        List<Message> messages = List.of(
            assistant(
                call("call-1", "read", Map.of("path", "a.txt")),
                call("call-2", "bash", Map.of("command", "false")),
                call("call-3", "grep", Map.of("pattern", "x"))),
            result("call-1", "read", "data", false),
            result("call-2", "bash", "boom", true));

        var calls = TranscriptEvents.toolCalls(messages);

        assertThat(calls).extracting(ToolCallRecord::id)
            .containsExactly("call-1", "call-2", "call-3");
        assertThat(calls).extracting(ToolCallRecord::status)
            .containsExactly("ok", "error", "pending");
        assertThat(calls.getFirst().succeeded()).isTrue();
        assertThat(calls.get(1).succeeded()).isFalse();
        assertThat(calls.get(2).succeeded()).isFalse();
        assertThat(calls.getFirst().arguments()).containsEntry("path", "a.txt");
    }

    @Test
    void ignoresSystemMessages() {
        var system = new Message.SystemMessage("prompt", null, Map.of(), List.of(), List.of());
        assertThat(TranscriptEvents.from(List.of(system))).isEmpty();
        assertThat(TranscriptEvents.toolCalls(List.of(system))).isEmpty();
    }
}
