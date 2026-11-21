package com.pijava.mcp.transport.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code docs/13} S3：Streamable HTTP 的 **body 限长与取消**（pi {@code streamable-http.ts} 的
 * {@code readBodyText}）。
 *
 * <p>两条行为：① JSON 响应受 {@code maxMessageBytes} 约束，超限**直接失败**而不是整包读进内存；
 * ② 错误体只读摘录，读完立刻关流取消剩余传输 —— 断言点是「流被关掉」，这正是 pi 注释里
 * 「不把巨大的错误体缓下来」的可观察形态。</p>
 */
class StreamableHttpBodyLimitsTest {

    private static final int MAX_BYTES = 1_024;

    /** 记录 close 的 body 流。 */
    private static final class TrackingStream extends ByteArrayInputStream {
        private final AtomicBoolean closed;

        TrackingStream(byte[] bytes, AtomicBoolean closed) {
            super(bytes);
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    private static StreamableHttpTransport transport(McpHttpFetch fetch) {
        var transport = new StreamableHttpTransport(new StreamableHttpTransportOptions(
                "https://mcp.test/rpc", Map.of(), fetch, false, MAX_BYTES, null, null));
        transport.start();
        return transport;
    }

    private static Map<String, Object> ping() {
        return Map.of("jsonrpc", "2.0", "id", 1, "method", "ping");
    }

    private static McpHttpFetch json(int status, String body, AtomicBoolean closed) {
        return (request, signal) -> new McpHttpResponse(status,
                Map.of("Content-Type", "application/json"), "application/json",
                new TrackingStream(body.getBytes(StandardCharsets.UTF_8), closed));
    }

    @Test
    void aJsonResponseOverTheLimitFailsAndStopsReading() {
        var closed = new AtomicBoolean();
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"pad\":\""
                + "x".repeat(4_096) + "\"}}";
        var transport = transport(json(200, body, closed));

        assertThatThrownBy(() -> transport.send(ping()))
                .isInstanceOf(IOException.class)
                .hasMessage("MCP JSON response exceeds 1024 bytes");
        assertThat(closed).as("超限后应关流（取消剩余传输）").isTrue();
    }

    @Test
    void aJsonResponseWithinTheLimitIsDelivered() throws Exception {
        var closed = new AtomicBoolean();
        var transport = transport(json(200,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}", closed));
        var messages = new java.util.concurrent.CopyOnWriteArrayList<Object>();
        transport.onMessage(messages::add);

        transport.send(ping());

        assertThat(messages).hasSize(1);
        assertThat(closed).isTrue();
    }

    @Test
    void aHugeErrorBodyIsTruncatedAndTheStreamClosed() {
        var closed = new AtomicBoolean();
        var transport = transport(json(500, "e".repeat(20_000), closed));

        assertThatThrownBy(() -> transport.send(ping()))
                .isInstanceOf(McpHttpError.class)
                .hasMessageContaining("status 500");
        assertThat(closed).as("错误体只读摘录，其余取消").isTrue();
    }
}
