package com.pijava.mcp.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;
import com.pijava.mcp.transport.StdioTransport;
import com.pijava.mcp.transport.http.StreamableHttpTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code createDefaultTransport} 与 {@code expandHome}（{@code runtime.ts:89-121}）
 * 以及 MCP App 过滤（{@code resources.ts:50-53}）。
 */
class McpDefaultTransportTest {

    private static McpServerEntry stdio(String command, List<String> args,
                                        Map<String, String> env, String cwd) {
        return new McpServerEntry("files",
                new McpServerConfig.Stdio(null, McpExposure.DIRECT, null, null, null, null,
                        command, args, env, cwd),
                Path.of("mcp.json"), McpScope.GLOBAL, null);
    }

    private static McpServerEntry http(String url, Map<String, String> headers) {
        return new McpServerEntry("files",
                new McpServerConfig.Http(null, McpExposure.DIRECT, null, null, null, null,
                        url, headers, null, null),
                Path.of("mcp.json"), McpScope.GLOBAL, null);
    }

    @Test
    void anHttpServerGetsItsHeadersResolved() {
        var transport = McpDefaultTransport.create(
                http("http://example.test/mcp", Map.of("X-Key", "literal")),
                Path.of("."), null, ConfigValueResolver.DEFAULT);
        assertThat(transport).isInstanceOf(StreamableHttpTransport.class);
    }

    @Test
    void anUnresolvableHeaderStopsTheTransportWithItsName() {
        var entry = http("http://example.test/mcp", Map.of("X-Key", "${MCP_TEST_MISSING_HEADER}"));
        assertThatThrownBy(() -> McpDefaultTransport.create(entry, Path.of("."), null,
                ConfigValueResolver.DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to resolve MCP server \"files\" header \"X-Key\""
                        + " from environment variable: MCP_TEST_MISSING_HEADER");
    }

    @Test
    void aStdioServerGetsItsEnvironmentResolved() {
        var entry = stdio("node", List.of("server.js"), Map.of("TOKEN", "literal"), null);
        var transport = McpDefaultTransport.create(entry, Path.of("."), null,
                ConfigValueResolver.DEFAULT);
        assertThat(transport).isInstanceOf(StdioTransport.class);
        assertThat(((StdioTransport) transport).stderr()).isEmpty();
    }

    @Test
    void theWorkingDirectoryIsAbsoluteAndNormalized() {
        var entry = stdio("node", null, null, "sub/../elsewhere");
        var transport = (StdioTransport) McpDefaultTransport.create(entry, Path.of("base"), null,
                ConfigValueResolver.DEFAULT);
        assertThat(transport).isNotNull();
        assertThat(Path.of("base").toAbsolutePath().resolve("elsewhere").normalize())
                .isEqualTo(Path.of("base/elsewhere").toAbsolutePath().normalize());
    }

    @Test
    void expandsTildeLikeAShell() {
        var home = System.getProperty("user.home");
        assertThat(McpDefaultTransport.expandHome("~")).isEqualTo(home);
        assertThat(McpDefaultTransport.expandHome("~/keys/token")).isEqualTo(
                Path.of(home, "keys/token").toString());
        assertThat(McpDefaultTransport.expandHome("/absolute/path")).isEqualTo("/absolute/path");
        assertThat(McpDefaultTransport.expandHome("relative")).isEqualTo("relative");
    }

    @Test
    void recognizesMcpAppResources() {
        assertThat(McpAppResources.isMcpAppResource("ui://widget", null, null)).isTrue();
        assertThat(McpAppResources.isMcpAppResource(null, "ui://widget/{id}", null)).isTrue();
        assertThat(McpAppResources.isMcpAppResource("file:///a.txt", null,
                "text/html;profile=mcp-app")).isTrue();
        assertThat(McpAppResources.isMcpAppResource("file:///a.txt", null,
                "text/html; profile=\"mcp-app\"")).isTrue();
        assertThat(McpAppResources.isMcpAppResource("file:///a.txt", null, "text/html")).isFalse();
        assertThat(McpAppResources.isMcpAppResource(
                new Resource("file:///a.txt", "a", null, null, "text/html", null, null, null)))
                .isFalse();
        assertThat(McpAppResources.isMcpAppResource(
                new ResourceTemplate("ui://widget/{id}", "w", null, null, null, null, null)))
                .isTrue();
    }
}
