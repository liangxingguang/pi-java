package com.pijava.agent.context;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OverflowDetectorTest {

    @Test
    void noOverflowWhenNoSignals() {
        assertThat(OverflowDetector.isOverflow(null, "stop", null, 200_000)).isFalse();
    }

    @Test
    void overflowFromContextLengthError() {
        assertThat(OverflowDetector.isOverflow(
                new RuntimeException("context length exceeded"), null, null, 200_000))
                .isTrue();
    }

    @Test
    void overflowFromTooLongError() {
        assertThat(OverflowDetector.isOverflow(
                new RuntimeException("prompt is too long"), null, null, 200_000))
                .isTrue();
    }

    @Test
    void overflowFromMaximumContextError() {
        assertThat(OverflowDetector.isOverflow(
                new RuntimeException("maximum context length reached"), null, null, 200_000))
                .isTrue();
    }

    @Test
    void overflowFromTokenLimitError() {
        assertThat(OverflowDetector.isOverflow(
                new RuntimeException("token limit exceeded"), null, null, 200_000))
                .isTrue();
    }

    @Test
    void overflowFromTokenCountExceedsWindow() {
        var usage = new StreamEvent.UsageInfo(150_000, 60_000,
                AssistantMessage.empty());
        assertThat(OverflowDetector.isOverflow(
                null, "stop", usage, 200_000)).isTrue();
    }

    @Test
    void overflowFromZeroOutputAndLength() {
        var usage = new StreamEvent.UsageInfo(1000, 0,
                AssistantMessage.empty());
        assertThat(OverflowDetector.isOverflow(
                null, "length", usage, 200_000)).isTrue();
    }

    @Test
    void normalErrorNotOverflow() {
        assertThat(OverflowDetector.isOverflow(
                new RuntimeException("connection refused"), null, null, 200_000))
                .isFalse();
    }

    @Test
    void normalStopNotOverflow() {
        var usage = new StreamEvent.UsageInfo(1000, 500,
                AssistantMessage.empty());
        assertThat(OverflowDetector.isOverflow(
                null, "stop", usage, 200_000)).isFalse();
    }
}
