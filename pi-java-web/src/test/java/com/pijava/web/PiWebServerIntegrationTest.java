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
 * {@code getModels} / {@code getSessions} 控制面往返，以及 Stage D
 * 会话重命名往返。
 */
class PiWebServerIntegrationTest {

    private static final String TOKEN = "test-token";

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
        var handle = PiWebServer.start(args, port, wsPort, TOKEN);
        try {
            assertConfigEndpoint(port, wsPort);
            assertWsRoundtrip(wsPort);
        } finally {
            handle.close();
        }
    }

    @Test
    void sessionRenameRoundtrip() throws Exception {
        var args = ArgsParser.parse(new String[]{"--mode", "web", "--offline", "--no-session"});
        int port = freePort();
        int wsPort = freePort();
        var handle = PiWebServer.start(args, port, wsPort, TOKEN);
        try {
            var received = new LinkedBlockingQueue<String>();
            var client = new WebSocketClient(
                    new URI("ws://localhost:" + wsPort + "/api/ws?token=" + TOKEN)) {
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
            assertThat(awaitType(received, "ready")).isEqualTo("ready");

            client.send("{\"type\":\"setSessionName\",\"name\":\"my-renamed\"}");
            assertThat(awaitContaining(received, "\"type\":\"sessionNameChanged\"")).isTrue();

            // 重命名生效：后续 getState 的 stateSync 反映新名字
            client.send("{\"type\":\"getState\"}");
            assertThat(awaitContaining(received, "\"sessionName\":\"my-renamed\"")).isTrue();

            client.close();
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
        assertThat(config.body()).contains("\"requiresAuth\":true");

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
        var client = new WebSocketClient(
                new URI("ws://localhost:" + wsPort + "/api/ws?token=" + TOKEN)) {
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

        // Stage B：文件浏览器 / git 控制面
        client.send("{\"type\":\"listDir\",\"path\":\"\"}");
        assertThat(awaitType(received, "dirListing")).isEqualTo("dirListing");

        client.send("{\"type\":\"gitStatus\"}");
        // GitService 行为已由 GitServiceTest 覆盖；此处只验证 WS 有响应
        //（gitStatusResult 或 error——测试 cwd 不保证是 JGit 可解析的仓库）。
        assertThat(awaitAny(received, "gitStatusResult", "error")).isIn("gitStatusResult", "error");

        client.close();
    }

    private static String awaitType(BlockingQueue<String> received, String type)
            throws Exception {
        return awaitAny(received, type);
    }

    private static String awaitAny(BlockingQueue<String> received, String... types)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String line = received.poll(2, TimeUnit.SECONDS);
            if (line == null) {
                continue;
            }
            for (var type : types) {
                if (line.contains("\"type\":\"" + type + "\"")) {
                    return type;
                }
            }
            // 忽略不相关消息（如 agentEvent），继续等目标类型
        }
        throw new AssertionError("Timed out waiting for " + String.join("|", types)
            + "; received so far: " + received);
    }

    /** 等待任意包含 {@code substring} 的消息，消费掉其他消息。 */
    private static boolean awaitContaining(BlockingQueue<String> received, String substring)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String line = received.poll(2, TimeUnit.SECONDS);
            if (line == null) {
                continue;
            }
            if (line.contains(substring)) {
                return true;
            }
        }
        throw new AssertionError("Timed out waiting for " + substring
            + "; received so far: " + received);
    }
}
