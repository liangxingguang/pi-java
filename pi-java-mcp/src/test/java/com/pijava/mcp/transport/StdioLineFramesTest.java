package com.pijava.mcp.transport;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StdioLineFramesTest {

    private record Result(ArrayList<byte[]> lines, ArrayList<Throwable> errors) {
    }

    private static Result pump(byte[] bytes, int maxMessageBytes) {
        var lines = new ArrayList<byte[]>();
        var errors = new ArrayList<Throwable>();
        StdioLineFrames.pump(new ByteArrayInputStream(bytes), maxMessageBytes,
                lines::add, errors::add);
        return new Result(lines, errors);
    }

    @Test
    void splitsLinesAndStripsCr() {
        var result = pump("a\r\nb\n".getBytes(StandardCharsets.UTF_8), 1024);
        assertThat(result.lines()).extracting(line -> new String(line, StandardCharsets.UTF_8))
                .containsExactly("a", "b");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void preservesMultiByteCharactersRegardlessOfDeliveryChunks() {
        // A 4-byte emoji plus JSON text, delivered one byte per read.
        var payload = ("{\"text\":\"😀\"}\n").getBytes(StandardCharsets.UTF_8);
        var result = pump(payload, 1024);
        assertThat(result.lines()).hasSize(1);
        assertThat(new String(result.lines().get(0), StandardCharsets.UTF_8))
                .isEqualTo("{\"text\":\"😀\"}");
    }

    @Test
    void rejectsOversizedAndIncompleteFrames() {
        var tooBig = pump("abc".getBytes(StandardCharsets.UTF_8), 2);
        assertThat(tooBig.errors()).hasSize(1);
        assertThat(tooBig.errors().get(0)).hasMessageContaining("exceeds 2 bytes");

        var incomplete = pump("abc".getBytes(StandardCharsets.UTF_8), 1024);
        assertThat(incomplete.errors()).hasSize(1);
        assertThat(incomplete.errors().get(0)).hasMessageContaining("incomplete JSON-RPC");
    }
}
