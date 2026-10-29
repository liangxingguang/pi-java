package com.pijava.ai.provider;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RadiusGatewayClientTest {

    @Test
    void gatewayLoadsBearerAndMapsCompleteModel() throws Exception {
        try (var server = new FakeRadiusServer()) {
            server.enqueue(200, """
                {"baseUrl":"http://radius.test","models":[
                  {"id":"balanced","name":"Balanced","reasoning":true,
                   "thinkingLevelMap":{"high":null,"max":"maximum"},
                   "input":["text","image"],"cost":{"input":1,"output":2,"cacheRead":0.1,"cacheWrite":0.2},
                   "contextWindow":1000,"maxTokens":100}
                ]}
                """);
            var client = new RadiusGatewayClient(server.gateway(), HttpClient.newHttpClient());

            var config = client.load("secret");

            assertThat(server.lastAuthorization).isEqualTo("Bearer secret");
            assertThat(config.baseUrl()).isEqualTo("http://radius.test");
            assertThat(config.models()).hasSize(1);
            var model = config.models().getFirst();
            assertThat(model.id().modelName()).isEqualTo("balanced");
            assertThat(model.baseUrl()).isEqualTo("http://radius.test");
            assertThat(model.api()).isEqualTo("pi-messages");
            assertThat(model.pricing().inputPrice()).isEqualTo(1.0);
            assertThat(model.pricing().outputPrice()).isEqualTo(2.0);
            assertThat(model.pricing().cacheReadPrice()).isEqualTo(0.1);
            assertThat(model.pricing().cacheWritePrice()).isEqualTo(0.2);
            assertThat(model.thinkingLevelMap().explicitlyUnsupported(
                com.pijava.ai.thinking.ModelThinkingLevel.of(new com.pijava.ai.thinking.ThinkingLevel.High())))
                .isTrue();
        }
    }

    @Test
    void invalidModelsAreFilteredAndMissingBaseUrlIsRejected() throws Exception {
        try (var server = new FakeRadiusServer()) {
            server.enqueue(200, """
                {"baseUrl":"http://radius.test","models":[
                  {"id":"good","name":"Good","reasoning":false,"input":["text"],
                   "cost":{"input":0,"output":0},"contextWindow":10,"maxTokens":5},
                  {"id":"bad"}
                ]}
                """);
            var config = new RadiusGatewayClient(server.gateway(), HttpClient.newHttpClient()).load(null);

            assertThat(config.models()).extracting(model -> model.id().modelName())
                .containsExactly("good");
            assertThat(server.lastAuthorization).isNull();
        }
        try (var server = new FakeRadiusServer()) {
            server.enqueue(200, "{\"models\":[]}");
            assertThatThrownBy(() -> new RadiusGatewayClient(
                server.gateway(), HttpClient.newHttpClient()).load(null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Invalid Radius config from " + server.gateway());
        }
    }

    @Test
    void httpErrorTruncatesBodyTo512CharactersPlusEllipsis() throws Exception {
        try (var server = new FakeRadiusServer()) {
            server.enqueue(500, "x".repeat(600));
            assertThatThrownBy(() -> new RadiusGatewayClient(
                server.gateway(), HttpClient.newHttpClient()).load(null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Could not load Radius config from " + server.gateway() + ": 500: ")
                .hasMessageEndingWith("…");
        }
    }

    /** Minimal controllable gateway with request capture. */
    private static final class FakeRadiusServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> bodies = new ArrayList<>();
        private volatile String lastAuthorization;

        private FakeRadiusServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/config", exchange -> {
                lastAuthorization = exchange.getRequestHeaders().getFirst("authorization");
                var body = bodies.removeFirst().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        private int status;

        private void enqueue(int status, String body) {
            this.status = status;
            bodies.add(body);
        }

        private String gateway() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
