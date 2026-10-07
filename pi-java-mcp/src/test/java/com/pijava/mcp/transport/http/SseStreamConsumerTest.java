package com.pijava.mcp.transport.http;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseStreamConsumerTest {

    private static final int MAX = 16 * 1024 * 1024;

    private record Result(ArrayList<SseEvent> events, ArrayList<String> ids,
                          ArrayList<Long> retries) {
    }

    private static Result consume(String raw) throws Exception {
        var events = new ArrayList<SseEvent>();
        var ids = new ArrayList<String>();
        var retries = new ArrayList<Long>();
        SseStreamConsumer.consume(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), MAX,
                new SseStreamConsumer.Handler() {
                    @Override
                    public void onEvent(SseEvent event) {
                        events.add(event);
                    }

                    @Override
                    public void onId(String id) {
                        ids.add(id);
                    }

                    @Override
                    public void onRetry(long retryMs) {
                        retries.add(retryMs);
                    }
                });
        return new Result(events, ids, retries);
    }

    @Test
    void parsesEventsWithCommentsAndCustomNames() throws Exception {
        var raw = ": comment line\n"
                + "event: update\n"
                + "data: hello\n"
                + "id: 42\n"
                + "retry: 250\n\n";
        var result = consume(raw);
        assertThat(result.events()).containsExactly(new SseEvent("update", "hello", "42"));
        assertThat(result.ids()).containsExactly("42");
        assertThat(result.retries()).containsExactly(250L);
    }

    @Test
    void joinsMultipleDataLinesWithNewlineAndStripsCr() throws Exception {
        var raw = "data: a\r\n"
                + "data: b\r\n\r\n";
        var result = consume(raw);
        assertThat(result.events()).containsExactly(new SseEvent(null, "a\nb", null));
    }

    @Test
    void dispatchesTrailingDataAtEofWithoutBlankLine() throws Exception {
        var result = consume("data: tail");
        assertThat(result.events()).containsExactly(new SseEvent(null, "tail", null));
    }

    @Test
    void blankLineWithoutDataProducesNoEvent() throws Exception {
        var result = consume("id: 9\n\n");
        assertThat(result.events()).isEmpty();
        assertThat(result.ids()).containsExactly("9");
    }

    @Test
    void rejectsEventsExceedingMaxBytes() {
        var raw = "data: " + "x".repeat(100) + "\n\n";
        assertThatThrownBy(() -> SseStreamConsumer.consume(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), 50,
                new SseStreamConsumer.Handler() {
                    @Override
                    public void onEvent(SseEvent event) {
                    }
                })).hasMessageContaining("exceeds 50 bytes");
    }
}
