package com.pijava.ai.provider;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.protocol.PiMessagesApi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B224 §4.4：models.json 自定义 provider 的 {@code "api": "pi-messages"}
 * 此前<b>加载认、用时拒</b> —— {@code Protocol.PI_MESSAGES} 与 fromWire 都在，
 * 但 {@code ModelsJsonProvider.createChatApi} 的 switch 没有这一臂。
 * 补臂后自定义 Radius 兼容网关首次生产可达。
 */
class ModelsJsonPiMessagesTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void piMessagesApiIsRoutableFromModelsJson() throws Exception {
        var p = provider("""
            {
              "providers": {
                "radius": {
                  "baseUrl": "https://radius.example.com/v1",
                  "apiKey": "sk-inline",
                  "api": "pi-messages",
                  "models": [{"id": "auto"}]
                }
              }
            }
            """);

        var api = (ChatApi) p.createApi(ChatApi.class, ApiOptions.defaults());

        assertThat(api).isInstanceOf(PiMessagesApi.class);
    }

    @Test
    void piMessagesProviderEndToEndUsesPiErrorFormat() throws Exception {
        startJsonErrorServer(401,
            "{\"error\":{\"message\":\"Token expired\",\"code\":\"unauthorized\"}}");
        var p = provider("""
            {
              "providers": {
                "radius": {
                  "baseUrl": "%s",
                  "apiKey": "sk-inline",
                  "api": "pi-messages",
                  "models": [{"id": "auto"}]
                }
              }
            }
            """.formatted(serverBaseUrl()));

        var api = (ChatApi) p.createApi(ChatApi.class,
            new ApiOptions("", "", Duration.ofSeconds(5), 0, Map.of()));
        var request = StreamRequest.of(ModelId.of("radius", "auto"),
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))));

        var result = api.send(request, ApiOptions.defaults());

        assertThat(result).isInstanceOf(Message.AssistantMessage.class);
        var msg = (Message.AssistantMessage) result;
        assertThat(msg.stopReason()).isEqualTo("error");
        assertThat(msg.errorMessage())
            .isEqualTo("401 Unauthorized: Token expired (unauthorized)");
    }

    // ── 脚手架 ───────────────────────────────────────────────────────────

    private ModelsJsonProvider provider(String json) throws Exception {
        var path = java.nio.file.Files.createTempFile("models", ".json");
        java.nio.file.Files.writeString(path, json);
        var config = ModelsJsonConfig.load(path);
        var list = config.buildProviders();
        return list.stream()
            .filter(p -> p.name().equals("radius"))
            .map(p -> (ModelsJsonProvider) p)
            .findFirst().orElseThrow();
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void startJsonErrorServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages", exchange -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }
}
