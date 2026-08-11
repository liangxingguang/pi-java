package com.pijava.ai.stream;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StreamEvent} sealed hierarchy — all 13 event types.
 */
class StreamEventTest {

    private static final AssistantMessage EMPTY = AssistantMessage.empty();

    // ── Lifecycle ────────────────────────────────────────────

    @Test
    void startEvent() {
        var partial = EMPTY;
        var event = new StreamEvent.Start(partial);

        assertThat(event.partial()).isSameAs(partial);
    }

    // ── Text block ───────────────────────────────────────────

    @Test
    void textStart() {
        var partial = EMPTY.withContent(List.of(new ContentBlock.TextContent("")));
        var event = new StreamEvent.TextStart(0, partial);

        assertThat(event.contentIndex()).isEqualTo(0);
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void textDelta() {
        var partial = EMPTY.withContent(List.of(new ContentBlock.TextContent("Hello")));
        var event = new StreamEvent.TextDelta(0, "Hello", partial);

        assertThat(event.contentIndex()).isEqualTo(0);
        assertThat(event.delta()).isEqualTo("Hello");
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void textEnd() {
        var partial = EMPTY.withContent(List.of(new ContentBlock.TextContent("Hello World")));
        var event = new StreamEvent.TextEnd(0, "Hello World", partial);

        assertThat(event.contentIndex()).isEqualTo(0);
        assertThat(event.text()).isEqualTo("Hello World");
        assertThat(event.partial()).isSameAs(partial);
    }

    // ── Thinking block ───────────────────────────────────────

    @Test
    void thinkingStart() {
        var partial = EMPTY.withContent(List.of(new ContentBlock.TextContent("")));
        var event = new StreamEvent.ThinkingStart(1, partial);

        assertThat(event.contentIndex()).isEqualTo(1);
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void thinkingDelta() {
        var partial = EMPTY.withContent(List.of(new ContentBlock.TextContent("thinking...")));
        var event = new StreamEvent.ThinkingDelta(1, "thinking...", partial);

        assertThat(event.contentIndex()).isEqualTo(1);
        assertThat(event.delta()).isEqualTo("thinking...");
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void thinkingEnd() {
        var partial = EMPTY.withContent(List.of(new ContentBlock.TextContent("full thinking")));
        var event = new StreamEvent.ThinkingEnd(1, "full thinking", partial);

        assertThat(event.contentIndex()).isEqualTo(1);
        assertThat(event.thinking()).isEqualTo("full thinking");
        assertThat(event.partial()).isSameAs(partial);
    }

    // ── Tool call ────────────────────────────────────────────

    @Test
    void toolCallStart() {
        var partial = EMPTY.withContent(List.of(
                new ContentBlock.ToolUseContent("", "", Map.of())));
        var event = new StreamEvent.ToolCallStart(0, partial);

        assertThat(event.contentIndex()).isEqualTo(0);
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void toolCallDelta() {
        var partial = EMPTY.withContent(List.of(
                new ContentBlock.ToolUseContent("toolu_01", "read", Map.of("path", "/f"))));
        var event = new StreamEvent.ToolCallDelta(0, "toolu_01", "{\"path\":", partial);

        assertThat(event.contentIndex()).isEqualTo(0);
        assertThat(event.id()).isEqualTo("toolu_01");
        assertThat(event.jsonDelta()).isEqualTo("{\"path\":");
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void toolCallEndDefensiveCopy() {
        var args = new java.util.HashMap<String, Object>();
        args.put("path", "/src/main.java");
        var partial = EMPTY.withContent(List.of(
                new ContentBlock.ToolUseContent("toolu_01", "read", args)));
        var event = new StreamEvent.ToolCallEnd(0, "toolu_01", "read", args, partial);

        args.put("path", "/modified");
        assertThat(event.arguments()).containsEntry("path", "/src/main.java");
        assertThat(event.partial()).isSameAs(partial);
    }

    // ── Usage ────────────────────────────────────────────────

    @Test
    void usageInfo() {
        var partial = EMPTY;
        var event = new StreamEvent.UsageInfo(100, 50, partial);

        assertThat(event.inputTokens()).isEqualTo(100);
        assertThat(event.outputTokens()).isEqualTo(50);
        assertThat(event.partial()).isSameAs(partial);
    }

    // ── Terminal events ──────────────────────────────────────

    @Test
    void streamDone() {
        var usage = new StreamEvent.UsageInfo(200, 100, null);
        var partial = EMPTY.withStopReason("end_turn");
        var event = new StreamEvent.StreamDone("end_turn", usage, partial);

        assertThat(event.reason()).isEqualTo("end_turn");
        assertThat(event.usage()).isSameAs(usage);
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void streamDoneWithoutUsage() {
        var partial = EMPTY.withStopReason("stop");
        var event = new StreamEvent.StreamDone("stop", null, partial);

        assertThat(event.reason()).isEqualTo("stop");
        assertThat(event.usage()).isNull();
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void streamError() {
        var error = new RuntimeException("connection refused");
        var partial = AssistantMessage.empty();
        var event = new StreamEvent.StreamError("error", error, partial);

        assertThat(event.reason()).isEqualTo("error");
        assertThat(event.error()).isSameAs(error);
        assertThat(event.partial()).isSameAs(partial);
    }

    @Test
    void streamErrorAborted() {
        var error = new InterruptedException("aborted");
        var partial = EMPTY.withStopReason("aborted");
        var event = new StreamEvent.StreamError("aborted", error, partial);

        assertThat(event.reason()).isEqualTo("aborted");
        assertThat(event.partial().stopReason()).isEqualTo("aborted");
    }

    // ── Partial access via sealed interface ──────────────────

    @Test
    void allEventsCarryPartial() {
        var partial = AssistantMessage.empty();
        StreamEvent[] events = {
            new StreamEvent.Start(partial),
            new StreamEvent.TextStart(0, partial),
            new StreamEvent.TextDelta(0, "hi", partial),
            new StreamEvent.TextEnd(0, "hi", partial),
            new StreamEvent.ThinkingStart(0, partial),
            new StreamEvent.ThinkingDelta(0, "th", partial),
            new StreamEvent.ThinkingEnd(0, "th", partial),
            new StreamEvent.ToolCallStart(0, partial),
            new StreamEvent.ToolCallDelta(0, "id", "{}", partial),
            new StreamEvent.ToolCallEnd(0, "id", "n", Map.of(), partial),
            new StreamEvent.UsageInfo(0, 0, partial),
            new StreamEvent.StreamDone("stop", null, partial),
            new StreamEvent.StreamError("error", new Exception(), partial),
        };

        for (var event : events) {
            assertThat(event.partial()).as("partial for " + event.getClass().getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("test")))
                .withStopReason("stop");
        var event = new StreamEvent.StreamDone("stop", null, partial);

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"type\":\"done\"");
        assertThat(json).contains("\"reason\":\"stop\"");

        var parsed = mapper.readValue(json, StreamEvent.class);
        assertThat(parsed).isInstanceOf(StreamEvent.StreamDone.class);
        var done = (StreamEvent.StreamDone) parsed;
        assertThat(done.reason()).isEqualTo("stop");
        assertThat(done.partial().stopReason()).isEqualTo("stop");
    }
}
