package com.pijava.ai.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-20（原 docs/68）第 1 步：pi-messages 车道<b>不重试</b>。
 *
 * <p>pi 的 pi-messages 低层 {@code stream} 是单次 {@code fetch}，失败直接推
 * error 帧（{@code pi-messages.ts:393-423}）；因此服务器首次返回 500 时，
 * 本车道必须以 error 终局、且服务器只收到 <b>1 次</b>请求。
 */
class PiMessagesNoRetryTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void serverErrorIsNotRetriedAndEndsInError() throws Exception {
        var hits = new AtomicInteger();
        var baseUrl = startFlakyServer(hits);
        var api = new PiMessagesApi(
            new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(10), 0, Map.of()),
            "PI_MESSAGES_API_KEY");

        var events = new ArrayList<StreamEvent>();
        var request = StreamRequest.of(ModelId.of("pi-messages", "gateway-model"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))));
        try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamError);
        assertThat(hits.get())
            .as("pi-messages 在 pi 是单次 fetch：500 不得触发重试")
            .isEqualTo(1);
    }

    private String startFlakyServer(AtomicInteger hits) throws IOException {
        String successSse = "data: {\"type\":\"done\",\"reason\":\"stop\",\"usage\":null}\n\n";
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/messages", exchange -> {
            int attempt = hits.incrementAndGet();
            if (attempt == 1) {
                byte[] body = "transient".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            byte[] body = successSse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }
}
