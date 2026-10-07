package com.pijava.mcp.runtime;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpClient;
import com.pijava.mcp.McpClientOptions;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.protocol.Root;
import com.pijava.mcp.transport.McpTransport;
import com.pijava.mcp.transport.StdioTransport;

/**
 * Opening and watching a connection: the half of {@link McpServerConnection} that is about the
 * client rather than about serving calls (pi {@code runtime.ts:353-461}).
 */
final class McpConnector {

    /** Delays between attempts to connect to an HTTP server that failed with a transient error. */
    private static final long[] CONNECT_RETRY_DELAYS_MS = {250, 1_000};

    /** How much of a failed stdio server's stderr is kept ({@code runtime.ts:48}). */
    private static final int STDERR_TAIL_CHARS = 2_000;

    private McpConnector() {
    }

    /** Connect, retrying a transient HTTP failure ({@code runtime.ts:353-370}). */
    static McpClient open(McpServerConnection conn) throws Exception {
        conn.state = McpServerState.CONNECTING;
        conn.changed();
        var retries = conn.entry.config() instanceof McpServerConfig.Http
                ? CONNECT_RETRY_DELAYS_MS : new long[0];
        for (var attempt = 0; ; attempt++) {
            conn.stderrTail = null;
            try {
                return connectOnce(conn);
            } catch (Exception failure) {
                var delay = attempt < retries.length ? retries[attempt] : -1L;
                if (conn.closed || delay < 0 || !McpConnectionSupport.isTransientError(failure)) {
                    throw connectFailed(conn, failure);
                }
                Thread.sleep(delay);
                if (conn.closed) {
                    throw connectFailed(conn, failure);
                }
            }
        }
    }

    /** One connection attempt and its setup ({@code runtime.ts:372-419}). */
    private static McpClient connectOnce(McpServerConnection conn) throws Exception {
        var client = new McpClient(new McpClientOptions(
                conn.options.clientName(), conn.options.clientVersion(), null, null, null,
                conn.timeoutMs(),
                new McpClientOptions.Roots.Fixed(List.of(
                        new Root(McpConnectionSupport.fileUrl(conn.cwd),
                                McpConnectionSupport.baseName(conn.cwd))))));
        var serverLog = conn.options.log();
        if (serverLog != null) {
            client.onNotification("notifications/message",
                    params -> serverLog.write(conn.entry.name(), params));
        }
        McpTransport transport = null;
        try {
            transport = conn.options.createTransport().create(conn.entry, conn.cwd, conn.authProvider);
            client.connect(transport);
            client.onNotification("notifications/tools/list_changed", params -> refreshTools(conn, client));
            client.onNotification("notifications/resources/list_changed",
                    params -> refreshResources(conn, client));
            var stdio = transport instanceof StdioTransport value ? value : null;
            client.onClose(() -> handleClientClose(conn, client, stdio));
            var capabilities = client.serverCapabilities();
            // Servers without the tools capability (prompts or resources only) do not answer
            // tools/list, and servers without the resources capability do not answer resources/list.
            var offersResources = capabilities != null && capabilities.resources() != null;
            var tools = capabilities != null && capabilities.tools() != null
                    ? client.listTools(McpRequestOptions.none()).get() : List.<McpTool>of();
            var listed = offersResources ? McpConnectionSupport.fetchResources(client)
                    : new McpConnectionSupport.ResourceLists(List.of(), List.of());
            if (conn.closed) {
                throw new IllegalStateException("shut down while connecting");
            }
            if (!"connected".equals(client.connectionState())) {
                throw new IllegalStateException("connection closed during setup");
            }
            conn.client = client;
            conn.tools = tools;
            conn.hasResources = offersResources;
            conn.resources = listed.resources();
            conn.resourceTemplates = listed.templates();
            var instructions = client.instructions();
            conn.instructions = instructions == null || instructions.isBlank()
                    ? null : instructions.strip();
            conn.state = McpServerState.CONNECTED;
            conn.error = null;
            conn.options.onTools().accept(conn);
            conn.changed();
            return client;
        } catch (Exception failure) {
            try {
                client.close().get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException ignored) {
                // Already failing.
            }
            if (transport instanceof StdioTransport stdio) {
                var tail = stdio.stderr().strip();
                conn.stderrTail = tail.length() <= STDERR_TAIL_CHARS
                        ? McpConnectionSupport.nullIfEmpty(tail)
                        : tail.substring(tail.length() - STDERR_TAIL_CHARS);
            }
            throw failure;
        }
    }

    /** {@code runtime.ts:421-430}. */
    private static Exception connectFailed(McpServerConnection conn, Throwable failure) {
        if (conn.needsSignIn(failure) && !conn.closed) {
            conn.markNeedsAuth();
            return new IllegalStateException(conn.signInRequiredMessage());
        }
        conn.state = conn.closed ? McpServerState.CLOSED : McpServerState.FAILED;
        var message = McpConnectionSupport.errorMessage(failure);
        conn.error = conn.stderrTail == null ? message : message + "\n" + conn.stderrTail;
        conn.changed();
        return new IllegalStateException("MCP server \"" + conn.entry.name()
                + "\" failed to connect: " + conn.error);
    }

    /** The transport dropped. The next call reconnects; until then the status shows why. */
    private static void handleClientClose(McpServerConnection conn, McpClient client,
                                          @Nullable StdioTransport stdio) {
        if (conn.client != client || conn.closed) {
            return;
        }
        conn.client = null;
        conn.state = McpServerState.DISCONNECTED;
        var tail = stdio == null ? null : McpConnectionSupport.nullIfEmpty(stdio.stderr().strip());
        conn.error = tail == null ? "Connection closed" : "Connection closed\n" + tail;
        conn.changed();
    }

    /** {@code runtime.ts:442-452}. */
    private static void refreshTools(McpServerConnection conn, McpClient client) {
        try {
            var listed = client.listTools(McpRequestOptions.none()).get();
            if (conn.client != client || conn.closed) {
                return;
            }
            conn.tools = listed;
            conn.options.onTools().accept(conn);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (java.util.concurrent.ExecutionException failure) {
            conn.error = "Failed to refresh tools: "
                    + McpConnectionSupport.errorMessage(McpConnectionSupport.unwrap(failure));
        }
        conn.changed();
    }

    /** {@code runtime.ts:454-461}. */
    private static void refreshResources(McpServerConnection conn, McpClient client) {
        try {
            var listed = McpConnectionSupport.fetchResources(client);
            if (conn.client != client || conn.closed) {
                return;
            }
            conn.resources = listed.resources();
            conn.resourceTemplates = listed.templates();
            conn.options.onTools().accept(conn);
        } catch (RuntimeException failure) {
            conn.error = "Failed to refresh resources: " + McpConnectionSupport.errorMessage(failure);
        }
        conn.changed();
    }
}
