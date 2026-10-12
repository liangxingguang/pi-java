package com.pijava.ai.protocol;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * B229（docs/52，pi {@code mistral-conversations.ts:321-324}）：等响应头超时 ⇒
 * 流以 {@code "Mistral response headers timed out after Nms"} 落 error 终局。
 * 用包私有接缝构造自定义 300ms 头超时（生产恒 300s）。
 */
class MistralHeaderTimeoutTest {

    @Test
    void stalledHeadersProduceMistralTimeoutError() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1_500); // 永不回状态行（1.5s 后 server.stop 断；超时 300ms）
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var options = new ApiOptions(baseUrl, "test-key", Duration.ofSeconds(5), 0, Map.of());
            var api = new MistralConversationsApi(options, Duration.ofMillis(300));
            var model = com.pijava.ai.catalog.ModelInfo.minimal(ModelId.of("mistral", "mistral-large"));
            var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
            var request = new StreamRequest(model, null, List.of(user), List.of(), -1, -1);

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                var result = api.send(request, options);
                assertThat(result).isInstanceOf(Message.AssistantMessage.class);
                var msg = (Message.AssistantMessage) result;
                assertThat(msg.stopReason()).isEqualTo(StopReason.ERROR);
                assertThat(msg.errorMessage())
                    .isEqualTo("Mistral response headers timed out after 300ms");
            });
        } finally {
            server.stop(0);
        }
    }
}
