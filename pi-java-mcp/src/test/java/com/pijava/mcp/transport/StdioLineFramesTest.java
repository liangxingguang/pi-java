package com.pijava.mcp.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/13} S2：按块扫描的 stdio 行帧（pi {@code transports/lines.ts}）。
 *
 * <p>重点是**跨块边界**与**大消息只复制一次**这两条：前者是正确性，后者是这次改动的动机。</p>
 */
class StdioLineFramesTest {

    /** 每次最多吐出 {@code max} 字节，制造任意的块边界。 */
    private static InputStream chunked(byte[] bytes, int max) {
        return new InputStream() {
            private int at;

            @Override
            public int read() {
                return at < bytes.length ? bytes[at++] & 0xff : -1;
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                if (at >= bytes.length) {
                    return -1;
                }
                int n = Math.min(Math.min(max, length), bytes.length - at);
                System.arraycopy(bytes, at, target, offset, n);
                at += n;
                return n;
            }
        };
    }

    private record Result(List<byte[]> frames, List<Throwable> errors) {
    }

    private static Result pump(InputStream stream, int maxMessageBytes) {
        var frames = new ArrayList<byte[]>();
        var errors = new ArrayList<Throwable>();
        StdioLineFrames.pump(stream, maxMessageBytes, frames::add, errors::add);
        return new Result(frames, errors);
    }

    @Test
    void reassemblesFramesSplitAcrossChunks() {
        var payload = ("{\"a\":\"" + "x".repeat(1_000) + "\"}\n").getBytes(StandardCharsets.UTF_8);

        // 每次只给 3 字节：几乎每个换行都落在块中间。
        var result = pump(chunked(payload, 3), 64 * 1024);

        assertThat(result.errors()).isEmpty();
        assertThat(result.frames()).hasSize(1);
        assertThat(new String(result.frames().get(0), StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":\"" + "x".repeat(1_000) + "\"}");
    }

    @Test
    void splitsMultipleFramesAndStripsCarriageReturns() {
        var payload = "one\r\ntwo\n\nthree\n".getBytes(StandardCharsets.UTF_8);

        var result = pump(chunked(payload, 8), 64 * 1024);

        assertThat(result.errors()).isEmpty();
        assertThat(result.frames()).extracting(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .containsExactly("one", "two", "", "three");
    }

    @Test
    void aLargeSingleLineArrivesAsOneFrame() {
        // 2 MiB 一条消息：旧实现逐字节处理，这里断言内容完整（性能是动机，正确性是底线）。
        var body = "y".repeat(2 * 1024 * 1024);
        var payload = (body + "\n").getBytes(StandardCharsets.UTF_8);

        var result = pump(new ByteArrayInputStream(payload), 4 * 1024 * 1024);

        assertThat(result.errors()).isEmpty();
        assertThat(result.frames()).hasSize(1);
        assertThat(result.frames().get(0)).hasSize(2 * 1024 * 1024);
    }

    @Test
    void aMessageOverTheLimitReportsAndStops() {
        var payload = ("z".repeat(200_000) + "\n").getBytes(StandardCharsets.UTF_8);

        var result = pump(new ByteArrayInputStream(payload), 1_000);

        assertThat(result.frames()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).isInstanceOf(IOException.class)
                .hasMessage("MCP message exceeds 1000 bytes");
    }

    @Test
    void anIncompleteTailAtEofReports() {
        var payload = "{\"partial\":true".getBytes(StandardCharsets.UTF_8);

        var result = pump(new ByteArrayInputStream(payload), 64 * 1024);

        assertThat(result.frames()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0))
                .hasMessage("MCP server closed with an incomplete JSON-RPC message");
    }
}
