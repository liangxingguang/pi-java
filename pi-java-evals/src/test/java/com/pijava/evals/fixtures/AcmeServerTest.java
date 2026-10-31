package com.pijava.evals.fixtures;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §10）：{@link AcmeServer} 的两条 SSE 车道、reset 与端口释放。
 */
class AcmeServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void servesPiMessagesSseFrames() throws Exception {
        try (var server = AcmeServer.start()) {
            var response = post(server.customBaseUrl() + "/messages", "{\"model\":\"fixture-chat\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("text/event-stream");
            assertThat(response.body())
                .contains("\"type\":\"text_start\"", "\"delta\":\"pong\"", "\"type\":\"done\"");
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.path()).isEqualTo(AcmeServer.MESSAGES_PATH);
                assertThat(request.body()).contains("fixture-chat");
            });
        }
    }

    @Test
    void servesOpenAiSseFrames() throws Exception {
        try (var server = AcmeServer.start()) {
            var response = post(server.openAiBaseUrl() + "/chat/completions",
                "{\"model\":\"fixture-chat\",\"stream\":true}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"finish_reason\":\"stop\"", "\"usage\"", "[DONE]");
            assertThat(server.requests()).singleElement()
                .satisfies(request -> assertThat(request.path())
                    .isEqualTo(AcmeServer.OPENAI_PATH));
        }
    }

    @Test
    void resetClearsRecordedRequests() throws Exception {
        try (var server = AcmeServer.start()) {
            post(server.customBaseUrl() + "/messages", "{}");
            assertThat(server.requests()).hasSize(1);
            server.reset();
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void closeReleasesPort() throws Exception {
        var server = AcmeServer.start();
        int port = server.port();
        server.close();
        assertThat(bind(port)).isTrue();
    }

    @Test
    void rejectsNonPostMethods() throws Exception {
        try (var server = AcmeServer.start()) {
            var request = HttpRequest.newBuilder(
                URI.create(server.customBaseUrl() + "/messages")).GET().build();
            var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(server.requests()).isEmpty();
        }
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean bind(int port) throws IOException {
        try {
            var probe = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            probe.stop(0);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
