package com.pijava.coding.agent.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P6-13: SessionShare — gist 上传（注入 mock gist 端点）。
 */
class SessionShareTest {

    @Test
    void missingTokenThrows() {
        var share = new SessionShare();
        assertThat(catchThrowable(() -> share.share("{}", null)))
            .isInstanceOf(Exception.class).hasMessageContaining("GITHUB_TOKEN");
    }

    @Test
    void uploadReturnsHtmlUrl() throws Exception {
        var server = gistServer(201, "{\"html_url\":\"https://gist.github.com/abc123\"}");
        try {
            var share = new SessionShare(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/gists");

            var url = share.share("{\"kind\":\"header\"}", "token-123");

            assertThat(url).isEqualTo("https://gist.github.com/abc123");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpErrorThrows() throws Exception {
        var server = gistServer(401, "{\"message\":\"Bad credentials\"}");
        try {
            var share = new SessionShare(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/gists");

            var thrown = catchThrowable(() -> share.share("{}", "bad"));

            assertThat(thrown).isInstanceOf(Exception.class)
                .hasMessageContaining("HTTP 401");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer gistServer(int status, String body) throws Exception {
        var server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/gists", exchange -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
