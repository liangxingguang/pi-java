package com.pijava.web;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端冒烟测试（Phase 7 设计 §8）：启动静态 + WS 双服务器，
 * 验证 {@code /api/config}、WS {@code ready}、{@code getState} /
 * {@code getModels} / {@code getSessions} 控制面往返。
 */
class PiWebServerIntegrationTest {

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void controlPlaneRoundtrip() throws Exception {
        var args = ArgsParser.parse(new String[]{"--mode", "web", "--offline", "--no-session"});
        int port = freePort();
        int wsPort = freePort();
        var handle = PiWebServer.start(args, port, wsPort);
        try {
            assertConfigEndpoint(port, wsPort);
            assertWsRoundtrip(wsPort);
        } finally {
            handle.close();
        }
    }

    private static void assertConfigEndpoint(int port, int wsPort) throws Exception {
        var client = HttpClient.newHttpClient();

        var config = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/config"))
                .timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(config.body()).contains("\"wsPort\":" + wsPort);

        // 静态前端：根路径返回 SPA index.html
        var page = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/"))
                .timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<div id=\"app\"></div>");
    }

    private static void assertWsRoundtrip(int wsPort) throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var client = new WebSocketClient(new URI("ws://localhost:" + wsPort + "/api/ws")) {
            @Override
            public void onOpen(ServerHandshake handshake) {
            }

            @Override
            public void onMessage(String message) {
                received.offer(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
            }

            @Override
            public void onError(Exception ex) {
            }
        };
        client.connectBlocking(10, TimeUnit.SECONDS);
        assertThat(client.isOpen()).isTrue();

        // 连接即收 ready（AgentSession 创建可能稍慢）
        var ready = awaitType(received, "ready");
        assertThat(ready).isEqualTo("ready");

        client.send("{\"type\":\"getState\"}");
        assertThat(awaitType(received, "stateSync")).isEqualTo("stateSync");

        client.send("{\"type\":\"getModels\"}");
        assertThat(awaitType(received, "models")).isEqualTo("models");

        client.send("{\"type\":\"getSessions\"}");
        assertThat(awaitType(received, "sessions")).isEqualTo("sessions");

        client.close();
    }

    private static String awaitType(BlockingQueue<String> received, String type)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String line = received.poll(2, TimeUnit.SECONDS);
            if (line == null) {
                continue;
            }
            if (line.contains("\"type\":\"" + type + "\"")) {
                return type;
            }
            // 忽略不相关消息（如 agentEvent），继续等目标类型
        }
        throw new AssertionError("Timed out waiting for " + type + "; received so far: " + received);
    }
}
