package com.pijava.mcp.transport;

import java.util.List;

import com.pijava.mcp.TestAwait;
import com.pijava.mcp.McpClient;
import com.pijava.mcp.McpClientOptions;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.fixture.StdioFixtureServer;
import com.pijava.mcp.fixture.StubbornFixtureServer;
import com.pijava.mcp.protocol.content.CallToolResult;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpStdioTest {

    /** Java executable of this running JVM. */
    private static String javaBin() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    private static StdioTransportOptions options(Class<?> fixture) {
        return new StdioTransportOptions(javaBin(), List.of(
                "-cp", System.getProperty("java.class.path"),
                fixture.getName()));
    }

    @Test
    void connectsToNewlineDelimitedServerAndCapturesStderr() throws Exception {
        var transport = new StdioTransport(options(StdioFixtureServer.class));
        var client = new McpClient(new McpClientOptions("stdio-test", "1.0.0"));
        client.connect(transport);

        var tools = client.listTools(McpRequestOptions.none()).get();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("echo");

        var result = client.callTool("echo", java.util.Map.of("text", "hello"),
                McpRequestOptions.none()).get();
        assertThat(result).isEqualTo(new CallToolResult(
                List.of(new com.pijava.mcp.protocol.content.McpContentBlock.Text(
                        "hello", null, null)),
                null, null, null));

        assertThat(transport.pid()).isPositive();
        TestAwait.waitFor(() -> transport.stderr().contains("stdio fixture ready"),
                "stderr ready marker");
        assertThat(transport.stderr()).contains("stdio fixture ready");

        client.close();
        assertThat(client.connectionState()).isEqualTo("closed");
    }

    @Test
    void killsServerIgnoringShutdownIncludingChildren() throws Exception {
        var options = new StdioTransportOptions(javaBin(), List.of(
                "-cp", System.getProperty("java.class.path"),
                StubbornFixtureServer.class.getName()),
                null, true, StdioTransportOptions.Stderr.PIPE, 100, 0);
        var transport = new StdioTransport(options);
        var client = new McpClient(new McpClientOptions("stdio-test", "1.0.0"));
        client.connect(transport);

        TestAwait.waitFor(() -> transport.stderr().contains("grandchild "),
                "grandchild marker");
        var match = java.util.regex.Pattern.compile("grandchild (\\d+)")
                .matcher(transport.stderr());
        assertThat(match.find()).isTrue();
        var grandchildPid = Long.parseLong(match.group(1));

        var started = System.currentTimeMillis();
        client.close();
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);

        TestAwait.waitFor(() -> ProcessHandle.of(grandchildPid).isEmpty(),
                "grandchild dead");
        assertThat(ProcessHandle.of(grandchildPid)).isEmpty();
    }
}
