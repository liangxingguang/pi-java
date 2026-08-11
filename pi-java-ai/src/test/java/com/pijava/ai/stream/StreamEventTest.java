package com.pijava.ai.stream;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StreamEvent} sealed hierarchy types.
 */
class StreamEventTest {

    @Test
    void textDeltaDefaultTypeIsText() {
        var delta = new StreamEvent.TextDelta("hello", StreamEvent.TextDelta.TEXT);

        assertThat(delta.text()).isEqualTo("hello");
        assertThat(delta.type()).isEqualTo(StreamEvent.TextDelta.TEXT);
    }

    @Test
    void textDeltaWithThinking() {
        var delta = new StreamEvent.TextDelta("let me think...",
                StreamEvent.TextDelta.THINKING);

        assertThat(delta.type()).isEqualTo(StreamEvent.TextDelta.THINKING);
    }

    @Test
    void textDeltaOfHelperDefaultsToText() {
        var delta = StreamEvent.TextDelta.of("hello");

        assertThat(delta.text()).isEqualTo("hello");
        assertThat(delta.type()).isEqualTo(StreamEvent.TextDelta.TEXT);
    }

    @Test
    void textDeltaBlankTypeDefaultsToText() {
        var delta = new StreamEvent.TextDelta("text", "  ");

        assertThat(delta.type()).isEqualTo(StreamEvent.TextDelta.TEXT);
    }

    @Test
    void toolCallStart() {
        var event = new StreamEvent.ToolCallStart("toolu_01", "read");

        assertThat(event.id()).isEqualTo("toolu_01");
        assertThat(event.name()).isEqualTo("read");
    }

    @Test
    void toolCallDelta() {
        var event = new StreamEvent.ToolCallDelta("toolu_01", "{\"path\":");

        assertThat(event.id()).isEqualTo("toolu_01");
        assertThat(event.jsonDelta()).isEqualTo("{\"path\":");
    }

    @Test
    void toolCallEndDefensiveCopy() {
        var args = new java.util.HashMap<String, Object>();
        args.put("path", "/src/main.java");
        var event = new StreamEvent.ToolCallEnd("toolu_01", "read", args);

        args.put("path", "/modified");
        assertThat(event.arguments()).containsEntry("path", "/src/main.java");
    }

    @Test
    void usageInfo() {
        var event = new StreamEvent.UsageInfo(100, 50);

        assertThat(event.inputTokens()).isEqualTo(100);
        assertThat(event.outputTokens()).isEqualTo(50);
    }

    @Test
    void streamDone() {
        var usage = new StreamEvent.UsageInfo(200, 100);
        var event = new StreamEvent.StreamDone("end_turn", usage);

        assertThat(event.stopReason()).isEqualTo("end_turn");
        assertThat(event.usage()).isSameAs(usage);
    }

    @Test
    void streamError() {
        var error = new RuntimeException("connection refused");
        var event = new StreamEvent.StreamError(error);

        assertThat(event.error()).isSameAs(error);
    }

    @Test
    void streamDoneWithoutUsage() {
        var event = new StreamEvent.StreamDone("stop", null);

        assertThat(event.stopReason()).isEqualTo("stop");
        assertThat(event.usage()).isNull();
    }
}
