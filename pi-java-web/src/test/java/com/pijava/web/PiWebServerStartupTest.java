package com.pijava.web;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PiWebServer#start} 的**启动契约**：返回时必须已经能连。
 *
 * <p>2026-10-04 全量 {@code mvn clean verify} 出现一次红：{@code PiWebServerIntegrationTest
 * .sessionRenameRoundtrip:182}（{@code assertThat(client.isOpen()).isTrue()}，0.111 s 即失败）
 * —— 客户端被**连接拒绝**得干干净净，服务端一条日志都没有。根因在 Java-WebSocket 1.5.3：</p>
 *
 * <ul>
 *   <li>{@code WebSocketServer.start()}（字节码 40–51）只 {@code new Thread(selector).start()}
 *       就返回，**真正的 bind 在 selector 线程里**（{@code run()} → {@code doBind()}，
 *       绑定+注册后才在字节码 150 调 {@code onStart()}）。</li>
 *   <li>绑定失败走 {@code run()} 的 catch → {@code handleFatal}（字节码 252）⇒ 只回调
 *       {@code onError}（本类只打日志），**不冒泡**。{@code selectorthread} 也是在
 *       {@code run()} 里才赋值的，所以紧随 {@code start()} 的 {@code stop()} 会撞
 *       {@code IllegalStateException}。</li>
 * </ul>
 *
 * <p>于是 {@code start()} 会返回一个**看起来健康、实际没有任何监听器**的句柄：端口被占时静默、
 * 端口空闲时也要靠调用方「等一会儿」才能连上。本用例把这两条都钉住。</p>
 */
class PiWebServerStartupTest {

    private static final String TOKEN = "test-token";

    private Path fakeHome;
    private String savedUserHome;

    @BeforeEach
    void isolateUserHome() throws Exception {
        fakeHome = Files.createTempDirectory("pi-web-startup-home");
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void restoreUserHome() throws Exception {
        if (savedUserHome != null) {
            System.setProperty("user.home", savedUserHome);
        }
        if (fakeHome != null) {
            try (var paths = Files.walk(fakeHome)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // logback 的文件 appender 可能还占着 —— 留给 OS 回收
                    }
                });
            }
        }
    }

    private static Args webArgs() {
        return ArgsParser.parse(new String[] {"--mode", "web", "--offline", "--no-session"});
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 端口被占时：必须显式抛错，而不是返回一个永远连不上的句柄。 */
    @Test
    void startFailsLoudlyWhenTheWebSocketPortIsAlreadyBound() throws Exception {
        int port = freePort();
        int wsPort = freePort();
        try (var squatter = new ServerSocket(wsPort)) {
            assertThatThrownBy(() -> PiWebServer.start(webArgs(), port, wsPort, TOKEN))
                .as("WS 端口 %d 已被占用，start() 不许静默返回", wsPort)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining(String.valueOf(wsPort));
        }
    }

    /**
     * {@code start()} 返回的**那一刻**，WS 端口就该已能接受 TCP 连接。
     *
     * <p>断言失败的信息是「第几次、端口多少」—— 因为这是竞态，稳定复现与否取决于调度。</p>
     */
    @Test
    void startReturnsOnlyOnceTheListenerAccepts() throws Exception {
        int attempts = 20;
        for (int i = 1; i <= attempts; i++) {
            int port = freePort();
            int wsPort = freePort();
            var handle = PiWebServer.start(webArgs(), port, wsPort, TOKEN);
            try {
                try (var probe = new Socket()) {
                    probe.connect(new InetSocketAddress("127.0.0.1", wsPort), 2000);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(
                    "第 " + i + "/" + attempts + " 次：start() 已返回但 ws 端口 " + wsPort
                        + " 拒绝连接（" + e.getMessage() + "）");
            } finally {
                handle.close();
            }
        }
    }
}
