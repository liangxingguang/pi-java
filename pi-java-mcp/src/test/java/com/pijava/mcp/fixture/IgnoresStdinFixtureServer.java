package com.pijava.mcp.fixture;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * MCP server fixture that reads stdin but refuses to exit when it closes
 * (pi {@code test/fixtures/exiting-parent-server.mjs} 的反面：一个「不理 stdin 关闭」的服务器）。
 *
 * <p>用途：验证关闭序列的**宽限**——关 stdin 之后 500ms 就该收到 SIGTERM 等价（Windows 上是
 * {@code destroy()}），而不是白等满 {@code closeTimeoutMs}。</p>
 */
public final class IgnoresStdinFixtureServer {

    private IgnoresStdinFixtureServer() {
    }

    /** Entry point. */
    public static void main(String[] args) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (reader.readLine() != null) {
            // 丢掉所有消息：本夹具不参与握手，只等被关闭。
        }
        // stdin 已关闭：刻意不退出，等传输层超时后强杀。
        Thread.sleep(60_000);
    }
}
