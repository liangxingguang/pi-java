package com.pijava.coding.agent.modes;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: PrintMode stdout/stderr output contract.
 */
class PrintModeTest {

    @Test
    void textDeltasStreamToStdout() {
        var out = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            PrintMode.renderEvent(new StreamEvent.TextDelta(
                0, "hello ", AssistantMessage.empty()), false);
            PrintMode.renderEvent(new StreamEvent.TextDelta(
                0, "world", AssistantMessage.empty()), false);
        } finally {
            System.setOut(original);
        }

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    @Test
    void toolCallEndEmitsNewline() {
        var out = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            PrintMode.renderEvent(new StreamEvent.ToolCallEnd(
                0, "id", "read", java.util.Map.of(), AssistantMessage.empty()), false);
        } finally {
            System.setOut(original);
        }

        assertThat(out.toString(StandardCharsets.UTF_8))
            .isEqualTo(System.lineSeparator());
    }

    @Test
    void streamErrorGoesToStderr() {
        var err = new ByteArrayOutputStream();
        var original = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            PrintMode.renderEvent(new StreamEvent.StreamError(
                "error", new IllegalStateException("boom"), AssistantMessage.empty()), false);
        } finally {
            System.setErr(original);
        }

        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("error", "boom");
    }

    @Test
    void verbosePrintsThinkingAndToolDetails() {
        var out = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            PrintMode.renderEvent(new StreamEvent.ThinkingDelta(
                0, "reasoning...", AssistantMessage.empty()), true);
            PrintMode.renderEvent(new StreamEvent.ToolCallEnd(
                0, "id", "read", java.util.Map.of("path", "a.txt"),
                AssistantMessage.empty()), true);
        } finally {
            System.setOut(original);
        }

        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("reasoning...", "read", "a.txt");
    }
}
