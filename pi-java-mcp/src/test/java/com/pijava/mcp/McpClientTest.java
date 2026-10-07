package com.pijava.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.protocol.McpVersion;
import com.pijava.mcp.protocol.ProgressNotification;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;
import com.pijava.mcp.protocol.ToolAnnotations;
import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.protocol.jsonrpc.McpAbortError;
import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;
import com.pijava.mcp.protocol.jsonrpc.McpTimeoutError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

    private static McpClient connect(RecordingTransport server) throws Exception {
        var client = new McpClient(new McpClientOptions("test-client", "2.0.0"));
        client.connect(server);
        return client;
    }

    /** Cause of a future expected to fail within 5 s. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("future completed normally");
        } catch (ExecutionException error) {
            return error.getCause();
        } catch (Exception error) {
            throw new AssertionError("future did not fail in time: " + error);
        }
    }

    private static Map<String, Object> wire(Object... kv) {
        var map = new LinkedHashMap<String, Object>();
        for (var i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void initializesConnectionBeforeExposingServerInformation() throws Exception {
        var server = RecordingTransport.create();
        var client = new McpClient(new McpClientOptions("test-client", "2.0.0"));
        client.connect(server);

        assertThat(client.connectionState()).isEqualTo("connected");
        assertThat(client.protocolVersion()).isEqualTo(McpVersion.LATEST);
        assertThat(client.serverInfo()).isEqualTo(
                new com.pijava.mcp.protocol.Implementation("test-server", "1.0.0", null));
        assertThat(client.serverCapabilities()).isEqualTo(
                new com.pijava.mcp.protocol.ServerCapabilities(
                        null, null, null, null,
                        new com.pijava.mcp.protocol.ServerCapabilities.Tools(true), null));
        assertThat(client.instructions()).isEqualTo("Use test tools.");

        var initParams = Map.of(
                "protocolVersion", McpVersion.LATEST,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test-client", "version", "2.0.0"));
        assertThat(server.messages()).containsExactly(
                wire("jsonrpc", "2.0", "id", 1L, "method", "initialize", "params", initParams),
                wire("jsonrpc", "2.0", "method", "notifications/initialized"));
        client.close();
    }

    @Test
    void paginatesToolsAndPreservesProtocolDefinitions() throws Exception {
        var server = RecordingTransport.create();
        server.setHandler("tools/list", params -> {
            var cursor = params == null ? null
                    : (String) ((Map<?, ?>) params).get("cursor");
            if (cursor == null) {
                return Map.of("tools", List.of(Map.of(
                        "name", "search", "description", "Search",
                        "inputSchema", Map.of("type", "object"))),
                        "nextCursor", "page-2");
            }
            return Map.of("tools", List.of(Map.of(
                    "name", "read",
                    "inputSchema", Map.of("type", "object"),
                    "outputSchema", Map.of("type", "object"),
                    "annotations", Map.of("readOnlyHint", true))),
                    "nextCursor", "");
        });
        var client = connect(server);
        assertThat(client.listTools(McpRequestOptions.none()).get()).containsExactly(
                new McpTool("search", null, "Search", Map.of("type", "object"),
                        null, null, null, null),
                new McpTool("read", null, null, Map.of("type", "object"),
                        Map.of("type", "object"),
                        new ToolAnnotations(null, true, null, null, null), null, null));
        client.close();
    }

    @Test
    void listsAndReadsResources() throws Exception {
        var server = RecordingTransport.create();
        server.setHandler("resources/list", params -> {
            var cursor = params == null ? null
                    : (String) ((Map<?, ?>) params).get("cursor");
            if (cursor == null) {
                return Map.of("resources", List.of(Map.of(
                        "uri", "file:///a", "name", "a",
                        "mimeType", "text/plain")), "nextCursor", "2");
            }
            return Map.of("resources", List.of(Map.of("uri", "file:///b")));
        });
        server.setHandler("resources/templates/list", params -> Map.of(
                "resourceTemplates", List.of(Map.of(
                        "uriTemplate", "repo://{owner}/{repo}", "name", "repo"))));
        server.setHandler("resources/read", params -> Map.of(
                "contents", List.of(Map.of(
                        "uri", ((Map<?, ?>) params).get("uri"), "text", "hello"))));

        var client = connect(server);
        var resourceA = new Resource("file:///a", "a", null, null,
                "text/plain", null, null, null);
        var resourceB = new Resource("file:///b", "file:///b", null, null,
                null, null, null, null);
        assertThat(client.listResources(McpRequestOptions.none()).get())
                .containsExactly(resourceA, resourceB);
        assertThat(client.listResourceTemplates(McpRequestOptions.none()).get())
                .containsExactly(new ResourceTemplate(
                        "repo://{owner}/{repo}", "repo", null, null, null, null, null));
        assertThat(client.listResourcesPage(null, McpRequestOptions.none()).get())
                .isEqualTo(new com.pijava.mcp.protocol.ListResourcesResult(
                        List.of(resourceA), "2", null));
        assertThat(client.listResourcesPage("2", McpRequestOptions.none()).get())
                .isEqualTo(new com.pijava.mcp.protocol.ListResourcesResult(
                        List.of(resourceB), null, null));
        assertThat(client.readResource("file:///a", McpRequestOptions.none()).get())
                .isEqualTo(new com.pijava.mcp.protocol.ReadResourceResult(
                        List.of(new com.pijava.mcp.protocol.content.ResourceContents.Text(
                                "file:///a", null, "hello", null)), null));

        server.setHandler("resources/read", params -> Map.of(
                "contents", List.of(Map.of("uri", "file:///a"))));
        assertThat(failureOf(client.readResource("file:///a", McpRequestOptions.none())))
                .isInstanceOf(com.pijava.mcp.protocol.jsonrpc.McpError.class)
                .hasMessage("Invalid contents in MCP resources/read result");
        server.setHandler("resources/list", params -> Map.of(
                "resources", List.of(Map.of("name", "no uri"))));
        assertThat(failureOf(client.listResources(McpRequestOptions.none())))
                .hasMessage("Invalid entry in MCP resources/list result");
        client.close();
    }

    @Test
    void returnsStructuredToolContentAndSurfacesJsonRpcErrors() throws Exception {
        var server = RecordingTransport.create();
        server.setHandler("tools/call", params -> {
            var p = (Map<?, ?>) params;
            if ("fail".equals(p.get("name"))) {
                throw new com.pijava.mcp.protocol.jsonrpc.McpError(
                        1234, "tool failed", Map.of("retryable", false));
            }
            return Map.of(
                    "content", List.of(Map.of("type", "text", "text", "ok")),
                    "structuredContent", Map.of(
                            "count", ((Map<?, ?>) p.get("arguments")).get("count")));
        });
        var client = connect(server);
        assertThat(client.callTool("count", Map.of("count", 3), McpRequestOptions.none()).get())
                .isEqualTo(new com.pijava.mcp.protocol.content.CallToolResult(
                        List.of(new com.pijava.mcp.protocol.content.McpContentBlock.Text(
                                "ok", null, null)),
                        Map.of("count", 3), null, null));

        var failure = failureOf(client.callTool("fail", null, McpRequestOptions.none()));
        assertThat(failure).isInstanceOf(com.pijava.mcp.protocol.jsonrpc.McpError.class);
        var mcpError = (com.pijava.mcp.protocol.jsonrpc.McpError) failure;
        assertThat(mcpError.code()).isEqualTo(1234);
        assertThat(mcpError).hasMessage("tool failed");
        assertThat(mcpError.data()).isEqualTo(Map.of("retryable", false));
        client.close();
    }

    @Test
    void renewsTimeoutOnProgress() throws Exception {
        var server = RecordingTransport.create();
        server.setHandler("tools/call", params -> {
            var token = ((Map<?, ?>) ((Map<?, ?>) params).get("_meta")).get("progressToken");
            Thread.startVirtualThread(() -> {
                TestAwait.sleep(100);
                server.serverSends(McpClientWires.notification("notifications/progress",
                        Map.of("progressToken", token, "progress", 1, "total", 2)));
            });
            TestAwait.sleep(200);
            return Map.of("content", List.of(Map.of("type", "text", "text", "done")));
        });
        var called = new CountDownLatch(1);
        var last = new AtomicReference<ProgressNotification>();
        var client = connect(server);
        var result = client.callTool("slow", Map.of(), new McpRequestOptions(
                null, 400, progress -> {
                    last.set(progress);
                    called.countDown();
                }));
        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(last.get()).isEqualTo(new ProgressNotification(2L, 1.0, 2.0, null));
        assertThat(result.get()).isEqualTo(new com.pijava.mcp.protocol.content.CallToolResult(
                List.of(new com.pijava.mcp.protocol.content.McpContentBlock.Text(
                        "done", null, null)), null, null, null));
        client.close();
    }

    @Test
    void cancelsAbortedAndTimedOutRequests() throws Exception {
        var server = RecordingTransport.create();
        var never = new CountDownLatch(1);
        server.setHandler("tools/call", params -> {
            TestAwait.await(never);
            return null;
        });
        var client = connect(server);

        var signal = AbortSignal.create();
        var aborted = client.callTool("wait", Map.of(), new McpRequestOptions(signal, 0, null));
        TestAwait.sleep(80);
        signal.abort();
        assertThat(failureOf(aborted)).isInstanceOf(McpAbortError.class);
        TestAwait.waitFor(() -> server.messages().stream().anyMatch(message ->
                "notifications/cancelled".equals(message.get("method"))
                        && ((Map<?, ?>) message.get("params")).get("requestId").equals(2L)
                        && "Aborted".equals(((Map<?, ?>) message.get("params")).get("reason"))),
                "cancelled notification for request 2");

        assertThat(failureOf(client.callTool("wait", Map.of(),
                new McpRequestOptions(null, 50, null)))).isInstanceOf(McpTimeoutError.class);
        client.close();
    }

    @Test
    void reportsTransportErrorsWithoutFailingPendingRequests() throws Exception {
        var server = RecordingTransport.create();
        var client = connect(server);
        var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        client.onError(errors::add);
        var respond = new CountDownLatch(1);
        server.setHandler("tools/call", params -> {
            TestAwait.await(respond, 5, TimeUnit.SECONDS);
            return Map.of("content", List.of());
        });
        var call = client.callTool("wait", null, McpRequestOptions.none());
        TestAwait.sleep(80);
        server.emitTransportError(new RuntimeException("stray log line"));
        respond.countDown();
        assertThat(call.get(5, TimeUnit.SECONDS))
                .isEqualTo(new com.pijava.mcp.protocol.content.CallToolResult(
                        List.of(), null, null, null));
        assertThat(errors).extracting(Throwable::getMessage)
                .containsExactly("stray log line");
        client.close();
    }

    @Test
    void acceptsServersWithAnOlderProtocolVersion() throws Exception {
        var server = RecordingTransport.create();
        server.setHandler("initialize", params -> Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "serverInfo", Map.of("name", "old-server", "version", "0.1.0")));
        var client = new McpClient(new McpClientOptions("test-client", "1.0.0"));
        client.connect(server);
        assertThat(client.protocolVersion()).isEqualTo("2024-11-05");
        client.close();

        var ancient = RecordingTransport.create();
        ancient.setHandler("initialize", params -> Map.of(
                "protocolVersion", "1999-01-01",
                "capabilities", Map.of(),
                "serverInfo", Map.of("name", "ancient-server", "version", "0.1.0")));
        var rejected = new McpClient(new McpClientOptions("test-client", "1.0.0"));
        assertThatThrownBy(() -> rejected.connect(ancient))
                .hasMessageContaining("unsupported protocol version");
        assertThat(rejected.connectionState()).isEqualTo("closed");
    }

    @Test
    void defaultsMissingToolResultContentToEmptyList() throws Exception {
        var server = RecordingTransport.create();
        server.setHandler("tools/call", params -> Map.of(
                "structuredContent", Map.of("ok", true)));
        var client = connect(server);
        assertThat(client.callTool("structured", null, McpRequestOptions.none()).get())
                .isEqualTo(new com.pijava.mcp.protocol.content.CallToolResult(
                        List.of(), Map.of("ok", true), null, null));

        server.setHandler("tools/call", params -> Map.of("content", "not a list"));
        assertThat(failureOf(client.callTool("broken", null, McpRequestOptions.none())))
                .hasMessage("Invalid MCP tools/call result");
        client.close();
    }

    @Test
    void doesNotSendCancelledNotificationForTimedOutInitialize() {
        var server = RecordingTransport.create();
        var never = new CountDownLatch(1);
        server.setHandler("initialize", params -> {
            TestAwait.await(never);
            return null;
        });
        var client = new McpClient(new McpClientOptions(
                "test-client", "1.0.0", null, null, null, 50, null));
        assertThatThrownBy(() -> client.connect(server))
                .isInstanceOf(McpTimeoutError.class);
        assertThat(server.messages()).noneMatch(message ->
                "notifications/cancelled".equals(message.get("method")));
    }

    @Test
    void notifiesCloseListenersOnceWhenTransportDrops() throws Exception {
        var server = RecordingTransport.create();
        var client = connect(server);
        var closed = new AtomicInteger();
        client.onClose(closed::incrementAndGet);
        var never = new CountDownLatch(1);
        server.setHandler("tools/call", params -> {
            TestAwait.await(never);
            return null;
        });
        var pending = client.callTool("wait", null, McpRequestOptions.none());
        TestAwait.sleep(80);
        server.close();
        assertThat(failureOf(pending))
                .isInstanceOf(McpConnectionClosedError.class)
                .hasMessage("MCP connection closed");
        assertThat(client.connectionState()).isEqualTo("closed");
        client.close();
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void answersRootsListAndDispatchesNotifications() throws Exception {
        var roots = new McpClientOptions.Roots.Fixed(
                List.of(new com.pijava.mcp.protocol.Root(
                        "file:///workspace", "workspace")));
        var server = RecordingTransport.create();
        var client = new McpClient(new McpClientOptions(
                "test-client", "1.0.0", null, null, null, 0, roots));
        client.connect(server);
        var changed = new CountDownLatch(1);
        client.onNotification("notifications/tools/list_changed", params -> changed.countDown());

        server.serverSends(wire("jsonrpc", "2.0", "id", "roots", "method", "roots/list"));
        server.serverSends(McpClientWires.notification("notifications/tools/list_changed", null));

        BooleanSupplier gotRootsResponse = () -> server.messages().stream().anyMatch(message ->
                "roots".equals(message.get("id"))
                        && ((Map<?, ?>) message.get("result")).get("roots") instanceof List<?>
                        && ((List<?>) ((Map<?, ?>) message.get("result")).get("roots"))
                                .contains(Map.of("uri", "file:///workspace", "name", "workspace")));
        TestAwait.waitFor(gotRootsResponse, "roots/list response");
        assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
        client.close();
    }
}
