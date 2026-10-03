package com.pijava.web;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.pijava.coding.agent.cli.ArgsParser;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage D 网关 token 鉴权：无 token / 错误 token 被拒（close 4401），
 * 正确 token 通过并收 {@code ready}。
 *
 * <p>⚠️ 与 {@link PiWebServerIntegrationTest} 同理：起服务会把 {@code user.home}
 * 下的默认会话根/设置当真，故每个用例把它指到临时目录。</p>
 */
class PiWebServerAuthTest {

    @TempDir
    Path fakeHome;

    private String savedUserHome;

    @BeforeEach
    void isolateUserHome() {
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (savedUserHome != null) {
            System.setProperty("user.home", savedUserHome);
        }
    }

    private static final String TOKEN = "secret-token";

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static PiWebServer.ServerHandle start(int port, int wsPort) throws Exception {
        var args = ArgsParser.parse(new String[]{"--mode", "web", "--offline", "--no-session"});
        return PiWebServer.start(args, port, wsPort, TOKEN);
    }

    /** 连接 WS 并返回服务端 close code（未关闭返回 -1）。 */
    private static int connectCloseCode(int wsPort, String query) throws Exception {
        var closed = new LinkedBlockingQueue<Integer>();
        var client = new WebSocketClient(new URI("ws://localhost:" + wsPort + "/api/ws" + query)) {
            @Override public void onOpen(ServerHandshake handshake) { }
            @Override public void onMessage(String message) { }
            @Override public void onClose(int code, String reason, boolean remote) {
                closed.offer(code);
            }
            @Override public void onError(Exception ex) { }
        };
        client.connectBlocking(10, TimeUnit.SECONDS);
        try {
            Integer code = closed.poll(10, TimeUnit.SECONDS);
            return code == null ? -1 : code;
        } finally {
            client.close();
        }
    }

    @Test
    void rejectsConnectionWithoutToken() throws Exception {
        int port = freePort();
        int wsPort = freePort();
        var handle = start(port, wsPort);
        try {
            assertThat(connectCloseCode(wsPort, "")).isEqualTo(4401);
        } finally {
            handle.close();
        }
    }

    @Test
    void rejectsConnectionWithWrongToken() throws Exception {
        int port = freePort();
        int wsPort = freePort();
        var handle = start(port, wsPort);
        try {
            assertThat(connectCloseCode(wsPort, "?token=wrong")).isEqualTo(4401);
        } finally {
            handle.close();
        }
    }

    @Test
    void acceptsConnectionWithValidToken() throws Exception {
        int port = freePort();
        int wsPort = freePort();
        var handle = start(port, wsPort);
        try {
            var received = new LinkedBlockingQueue<String>();
            var client = new WebSocketClient(
                    new URI("ws://localhost:" + wsPort + "/api/ws?token=" + TOKEN)) {
                @Override public void onOpen(ServerHandshake handshake) { }
                @Override public void onMessage(String message) { received.offer(message); }
                @Override public void onClose(int code, String reason, boolean remote) { }
                @Override public void onError(Exception ex) { }
            };
            client.connectBlocking(10, TimeUnit.SECONDS);
            assertThat(client.isOpen()).isTrue();
            String ready = received.poll(15, TimeUnit.SECONDS);
            assertThat(ready).isNotNull().contains("\"type\":\"ready\"");
            client.close();
        } finally {
            handle.close();
        }
    }
}
