package com.pijava.mcp.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.transport.http.McpHttpError;
import com.pijava.mcp.transport.http.McpSessionExpiredError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code McpServerConnection}（{@code runtime.ts:154-474}）。
 */
class McpServerConnectionTest {

    private static final long TIMEOUT_MS = 5_000;

    @TempDir
    Path cwd;

    private final List<McpServerConnection> changes = new ArrayList<>();

    private static McpServerEntry httpEntry() {
        return new McpServerEntry("files",
                new McpServerConfig.Http(null, McpExposure.DIRECT, null, null, null, null,
                        "http://example.test/mcp", null, null, null),
                Path.of("mcp.json"), McpScope.GLOBAL, null);
    }

    /** Build a connection onto a scripted server. */
    private McpServerConnection connection(ScriptedServer server) {
        return new McpServerConnection(new McpConnectionOptions(
                httpEntry(), cwd,
                (entry, directory, provider) -> server.connect(),
                new McpOAuthCredentialStore(new InMemoryAuthJsonBackend(), null),
                null, changes::add, null, null, "pi-java", "0.0.0", ConfigValueResolver.DEFAULT));
    }

    private static Map<String, Object> oneTool() {
        return Map.of("tools", List.of(ScriptedServer.tool("echo")));
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("condition never held");
    }

    @Test
    void connectsListsToolsAndCallsOne() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        server.answer("tools/call", params -> Map.of("content",
                List.of(Map.of("type", "text", "text", "hi"))));
        var connection = connection(server);

        assertThat(connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS).connectionState())
                .isEqualTo("connected");
        assertThat(connection.state()).isEqualTo(McpServerState.CONNECTED);
        assertThat(connection.error()).isNull();
        assertThat(connection.tools()).extracting(McpTool::name).containsExactly("echo");
        assertThat(changes).isNotEmpty();

        var result = connection.callTool("echo", Map.of("text", "hi"), McpRequestOptions.none())
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(result.content()).hasSize(1);

        connection.close().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(connection.state()).isEqualTo(McpServerState.CLOSED);
    }

    @Test
    void offersTheSessionDirectoryAsARoot() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        var roots = server.requestFromClient("roots/list", Map.of());
        assertThat(roots.toString())
                .contains(McpConnectionSupport.fileUrl(cwd))
                .contains(cwd.getFileName().toString());
    }

    @Test
    void trimsServerInstructions() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(connection.instructions()).isEqualTo("Scripted server.");
    }

    @Test
    void aToolCallIsNeverRetried() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        server.failAlways("tools/call", new McpHttpError(500, "boom"));

        assertThatThrownBy(() -> connection.callTool("echo", Map.of(), McpRequestOptions.none())
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .hasRootCauseInstanceOf(McpHttpError.class);
        assertThat(server.calls("tools/call")).isEqualTo(1);
    }

    @Test
    void aTransientFailureOnAReadOnlyCallIsRetriedOnce() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        server.answer("resources/list", params -> Map.of("resources", List.of()));
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        var before = server.calls("resources/list");
        server.failOnce("resources/list", new McpHttpError(503, "restarting"));

        assertThat(connection.allResources(McpRequestOptions.none())
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEmpty();
        assertThat(server.calls("resources/list")).isEqualTo(before + 2);
    }

    @Test
    void aNonTransientFailureOnAReadOnlyCallIsNotRetried() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        server.answer("resources/list", params -> Map.of("resources", List.of()));
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        var before = server.calls("resources/list");
        // 501 is the one 5xx pi does not treat as transient (runtime.ts:71).
        server.failAlways("resources/list", new McpHttpError(501, "not implemented"));

        assertThatThrownBy(() -> connection.allResources(McpRequestOptions.none())
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .hasRootCauseInstanceOf(McpHttpError.class);
        assertThat(server.calls("resources/list")).isEqualTo(before + 1);
    }

    @Test
    void anExpiredSessionIsReplayedOnAFreshConnection() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        server.answer("tools/call", params -> Map.of("content", List.of()));
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        server.failOnce("tools/call", new McpSessionExpiredError("gone"));

        assertThat(connection.callTool("echo", Map.of(), McpRequestOptions.none())
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();
        // One failed call plus one replay, on a second connection.
        assertThat(server.calls("tools/call")).isEqualTo(2);
        assertThat(server.calls("initialize")).isEqualTo(2);
    }

    @Test
    void aFailedConnectReportsTheStateAndTheReason() throws Exception {
        var server = new ScriptedServer();
        // A non-transient failure, so the HTTP connect retries do not stretch the test.
        server.failAlways("initialize", new McpHttpError(501, "boom"));
        var connection = connection(server);

        assertThatThrownBy(() -> connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .hasMessageContaining("failed to connect");
        assertThat(connection.state()).isEqualTo(McpServerState.FAILED);
        assertThat(connection.error()).contains("boom");
    }

    @Test
    void aListChangedNotificationRefreshesTheTools() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        server.answer("tools/list", params -> Map.of("tools",
                List.of(ScriptedServer.tool("echo"), ScriptedServer.tool("read"))));
        server.notifyClient("notifications/tools/list_changed", Map.of());

        waitUntil(() -> connection.tools().size() == 2);
        assertThat(connection.tools()).extracting(McpTool::name).containsExactly("echo", "read");
    }

    @Test
    void aServerWithResourcesListsThemWithoutTheApps() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        server.answer("resources/list", params -> Map.of("resources", List.of(
                Map.of("uri", "file:///a.txt", "name", "a"),
                Map.of("uri", "ui://widget", "name", "widget", "mimeType", "text/html"))));
        server.answer("resources/templates/list", params -> Map.of("resourceTemplates", List.of()));
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertThat(connection.hasResources()).isTrue();
        assertThat(connection.resources()).extracting(resource -> resource.uri())
                .containsExactly("file:///a.txt");
    }

    @Test
    void aServerWithoutTheResourcesCapabilityIsNeverAsked() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, false));
        server.answer("tools/list", params -> oneTool());
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertThat(connection.hasResources()).isFalse();
        assertThat(server.calls("resources/list")).isZero();
    }

    @Test
    void callingAShutDownConnectionFails() throws Exception {
        var server = new ScriptedServer();
        server.answer("initialize", params -> ScriptedServer.initializeResult(true, true));
        server.answer("tools/list", params -> oneTool());
        var connection = connection(server);
        connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        connection.close().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> connection.getClient().get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .hasMessageContaining("is shut down");
    }
}
