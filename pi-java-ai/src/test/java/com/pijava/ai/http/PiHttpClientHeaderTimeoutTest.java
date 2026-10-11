package com.pijava.ai.http;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * B229（docs/52，pi {@code 6b07b4e57}）：{@link PiHttpClient#postSse} 的头超时语义 ——
 * 超时只盖等响应头；头到达后 body 慢流不再被固定截止时间切断。
 */
class PiHttpClientHeaderTimeoutTest {

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;

        TestServer(boolean stallHeaders, long bodyDelayMs, String sseChunk) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                try {
                    if (stallHeaders) {
                        // 永不回状态行（4s 后 server.stop 断；产品超时 300ms 远在此之前）。
                        Thread.sleep(1_500);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0); // chunked
                    try (OutputStream os = exchange.getResponseBody()) {
                        // com.sun HttpServer 在 chunked 模式把头与**首个 body 块**一起刷
                        // （裸 socket 实测），故先发一条 SSE 注释心跳把状态行/头冲上线 ——
                        // SseIterator 按 pi 规则忽略 ':' 起始行；真实 SSE 服务器头是即刷的。
                        os.write(": heartbeat\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        os.flush();
                        Thread.sleep(bodyDelayMs);
                        os.write(("data: " + sseChunk + "\n\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        os.flush();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    @Test
    void throwsWhenHeadersDoNotArriveWithinTimeout() throws Exception {
        try (var server = new TestServer(true, 0, null);
                var http = PiHttpClient.builder().build()) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                var thrown = assertThrows(HeaderTimeoutException.class, () -> http.postSse(
                    server.url(), "{}", Map.of(), null, Duration.ofMillis(300)));
                assertThat(thrown).hasMessageContaining("300ms");
            });
        }
    }

    @Test
    void bodyDelayPastHeaderTimeoutStillDeliversEvents() throws Exception {
        try (var server = new TestServer(false, 1_200, "hello");
                var http = PiHttpClient.builder().build()) {
            // 头超时 300ms：body 在 1.2s 后才到 —— 头已释放，截止时间不许复活。
            Iterator<PiHttpClient.ServerSentEvent> events = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> http.postSse(server.url(), "{}", Map.of(), null, Duration.ofMillis(300)));
            assertThat(events.hasNext()).isTrue();
            assertThat(events.next().data()).isEqualTo("hello");
        }
    }

    @Test
    void legacyNullTimeoutKeepsWholeRequestShape() throws Exception {
        // headerTimeout == null ⇒ legacy 路径（pi-messages 在用），头超时不适用。
        try (var server = new TestServer(false, 100, "legacy");
                var http = PiHttpClient.builder().build()) {
            var events = http.postSse(server.url(), "{}", Map.of());
            assertThat(events.next().data()).isEqualTo("legacy");
        }
    }
}
