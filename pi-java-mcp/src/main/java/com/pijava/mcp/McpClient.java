package com.pijava.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.protocol.Implementation;
import com.pijava.mcp.protocol.McpVersion;
import com.pijava.mcp.protocol.InitializeParams;
import com.pijava.mcp.protocol.InitializeResult;
import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.protocol.ProgressNotification;
import com.pijava.mcp.protocol.ReadResourceResult;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;
import com.pijava.mcp.protocol.ServerCapabilities;
import com.pijava.mcp.protocol.jsonrpc.McpAbortError;
import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;
import com.pijava.mcp.protocol.jsonrpc.McpTimeoutError;
import com.pijava.mcp.protocol.content.CallToolResult;
import com.pijava.mcp.transport.McpTransport;

/**
 * A transport-neutral MCP client ({@code client.ts:153-615}).
 *
 * <p>All state is mutated under {@code lock}, mirroring the single event-loop
 * semantics; user futures are resolved and transport sends happen outside the
 * lock. Listeners must not re-enter client methods that take the lock.</p>
 */
public final class McpClient {

    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

    private enum State { IDLE, CONNECTING, CONNECTED, CLOSED }

    /** One outgoing request (client.ts:60-70). */
    record Pending(CompletableFuture<Object> future, long timeoutMs,
                   @Nullable AbortSignal signal,
                   @Nullable Runnable signalHandle,
                   boolean cancellable,
                   @Nullable Consumer<ProgressNotification> onProgress,
                   @Nullable Long progressToken) {
    }

    private final McpClientOptions options;
    private final McpClientInbox inbox;
    private final Object lock = new Object();
    private State state = State.IDLE;
    private @Nullable McpTransport transport;
    private long nextRequestId = 1;
    private @Nullable InitializeResult initialize;
    private final Map<Long, Pending> pending = new HashMap<>();
    private final Map<Long, ScheduledFuture<?>> timers = new HashMap<>();
    private final Map<Long, Long> progressRequests = new HashMap<>();
    private final Map<Object, AbortSignal> incoming = new HashMap<>();
    private final McpClientRegistrations registrations = new McpClientRegistrations();
    private final List<Runnable> disposers = new ArrayList<>();

    /** Create a client. */
    public McpClient(McpClientOptions options) {
        this.options = options;
        this.inbox = new McpClientInbox(this);
        var ignored = registrations.setRequestHandler("ping", (params, signal) -> Map.of());
        if (options.roots() != null) {
            ignored = registrations.setRequestHandler(
                    "roots/list", (params, signal) ->
                            Map.of("roots", McpClientShortcuts.roots(options)));
        }
    }


    /** Connection state as the wire word ("idle"/"connecting"/"connected"/"closed"). */
    public String connectionState() {
        synchronized (lock) {
            return state.name().toLowerCase();
        }
    }

    /** Server info, available once connected. */
    public @Nullable Implementation serverInfo() {
        synchronized (lock) {
            return initialize == null ? null : initialize.serverInfo();
        }
    }

    /** Server capabilities, available once connected. */
    public @Nullable ServerCapabilities serverCapabilities() {
        synchronized (lock) {
            return initialize == null ? null : initialize.capabilities();
        }
    }

    /** Server instructions, available once connected. */
    public @Nullable String instructions() {
        synchronized (lock) {
            return initialize == null ? null : initialize.instructions();
        }
    }

    /** Negotiated protocol version, available once connected. */
    public @Nullable String protocolVersion() {
        synchronized (lock) {
            return initialize == null ? null : initialize.protocolVersion();
        }
    }

    // --------------------------------------------------------------- connect

    /** Connect over {@code t} and initialize the session (client.ts:202-248). */
    public InitializeResult connect(McpTransport t) throws Exception {
        synchronized (lock) {
            if (state != State.IDLE) {
                throw new IllegalStateException("Cannot connect MCP client in " + state + " state");
            }
            state = State.CONNECTING;
            this.transport = t;
        }
        disposers.add(t.onMessage(inbox::handleMessage));
        disposers.add(t.onError(this::reportError));
        disposers.add(t.onClose(this::handleTransportClose));
        try {
            t.start();
            var capabilities = McpClientWires.withRootsCapability(
                    options.capabilities(), options.roots() != null);
            var clientInfo = new Implementation(options.name(), options.version(), options.title());
            var params = new InitializeParams(
                    options.protocolVersion() == null ? McpVersion.LATEST : options.protocolVersion(),
                    capabilities, clientInfo);
            var requestOptions = new McpRequestOptions(null, options.requestTimeoutMs(), null);
            var raw = await(requestInternal("initialize", params, requestOptions, true));
            var result = McpClientValidators.validateInitializeResult(raw);
            if (!McpVersion.SUPPORTED.contains(result.protocolVersion())) {
                throw new IllegalStateException(
                        "MCP server selected unsupported protocol version " + result.protocolVersion());
            }
            initialize = result;
            t.setProtocolVersion(result.protocolVersion());
            await(notifyInternal("notifications/initialized", null, true));
            synchronized (lock) {
                state = State.CONNECTED;
            }
            return result;
        } catch (Throwable error) {
            close().join();
            throw error;
        }
    }


    /** Send a request (client.ts:250-256). */
    public CompletableFuture<Object> request(String method, @Nullable Object params,
                                             McpRequestOptions options) {
        return requestInternal(method, params, options, false);
    }

    /** Send a notification (client.ts:258-260). */
    public CompletableFuture<Void> notify(String method, @Nullable Object params) {
        return notifyInternal(method, params, false);
    }

    private CompletableFuture<Object> requestInternal(
            String method, @Nullable Object rawParams, McpRequestOptions options,
            boolean allowConnecting) {
        McpTransport t;
        long id;
        Object params;
        Pending entry;
        synchronized (lock) {
            t = requireTransport(allowConnecting);
            if (options.signal() != null && options.signal().isAborted()) {
                throw new McpAbortError();
            }
            id = nextRequestId++;
            params = rawParams == null ? null : McpClientWires.toWire(rawParams);
            if (options.onProgress() != null) {
                params = McpClientWires.withProgressToken(params, id);
            }
            var future = new CompletableFuture<Object>();
            long timeoutMs = options.timeoutMs() > 0 ? options.timeoutMs()
                    : this.options.requestTimeoutMs() > 0 ? this.options.requestTimeoutMs()
                    : DEFAULT_REQUEST_TIMEOUT_MS;
            boolean cancellable = !method.equals("initialize");
            var abortListener = new Runnable() {
                @Override
                public void run() {
                    cancelPending(id, new McpAbortError(), cancellable, "Aborted");
                }
            };
            var signalHandle = options.signal() == null ? null
                    : options.signal().onAbort(abortListener);
            entry = new Pending(future, timeoutMs, options.signal(), signalHandle,
                    cancellable, options.onProgress(),
                    options.onProgress() == null ? null : id);
            pending.put(id, entry);
            if (entry.progressToken() != null) {
                progressRequests.put(entry.progressToken(), id);
            }
            armTimeout(id, timeoutMs, cancellable);
        }
        try {
            t.send(McpClientWires.request(id, method, params));
        } catch (Throwable sendError) {
            cancelPending(id, sendError, false);
        }
        return entry.future();
    }

    private CompletableFuture<Void> notifyInternal(
            String method, @Nullable Object rawParams, boolean allowConnecting) {
        McpTransport t;
        synchronized (lock) {
            t = requireTransport(allowConnecting);
        }
        var future = new CompletableFuture<Void>();
        try {
            t.send(McpClientWires.notification(method,
                    rawParams == null ? null : McpClientWires.toWire(rawParams)));
            future.complete(null);
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private McpTransport requireTransport(boolean allowConnecting) {
        if (transport != null
                && (state == State.CONNECTED
                        || (allowConnecting && state == State.CONNECTING))) {
            return transport;
        }
        throw new McpConnectionClosedError("MCP client is " + state.name().toLowerCase());
    }


    /** Register a handler for a server request (client.ts:262-267). */
    public Runnable setRequestHandler(String method, RequestHandler handler) {
        return registrations.setRequestHandler(method, handler);
    }

    /** Subscribe to a notification (client.ts:269-277). */
    public Runnable onNotification(String method, Consumer<Object> listener) {
        return registrations.onNotification(method, listener);
    }

    /** Subscribe to client errors (client.ts:279-282). */
    public Runnable onError(Consumer<Throwable> listener) {
        return registrations.onError(listener);
    }

    /** Subscribe once to close (client.ts:284-288). */
    public Runnable onClose(Runnable listener) {
        return registrations.onClose(listener);
    }


    /** Ping the server (client.ts:290-292). */
    public CompletableFuture<Void> ping(McpRequestOptions options) {
        return McpClientShortcuts.ping(this, options);
    }

    /** List every tool, through all pages (client.ts:294-296). */
    public CompletableFuture<List<McpTool>> listTools(McpRequestOptions options) {
        return McpClientShortcuts.listTools(this, options);
    }

    /** Every resource (client.ts:298-301). */
    public CompletableFuture<List<Resource>> listResources(McpRequestOptions options) {
        return McpClientShortcuts.listResources(this, options);
    }

    /** One page of resources (client.ts:303-308). */
    public CompletableFuture<com.pijava.mcp.protocol.ListResourcesResult> listResourcesPage(
            @Nullable String cursor, McpRequestOptions options) {
        return McpClientShortcuts.listResourcesPage(this, cursor, options);
    }

    /** Every resource template (client.ts:309-318). */
    public CompletableFuture<List<ResourceTemplate>> listResourceTemplates(McpRequestOptions options) {
        return McpClientShortcuts.listResourceTemplates(this, options);
    }

    /** One page of resource templates (client.ts:320-333). */
    public CompletableFuture<com.pijava.mcp.protocol.ListResourceTemplatesResult>
            listResourceTemplatesPage(@Nullable String cursor, McpRequestOptions options) {
        return McpClientShortcuts.listResourceTemplatesPage(this, cursor, options);
    }

    /** Read one resource (client.ts:335-337). */
    public CompletableFuture<ReadResourceResult> readResource(String uri, McpRequestOptions options) {
        return McpClientShortcuts.readResource(this, uri, options);
    }

    /** Call one tool (client.ts:376-384). */
    public CompletableFuture<CallToolResult> callTool(
            String name, @Nullable Map<String, Object> args, McpRequestOptions options) {
        return McpClientShortcuts.callTool(this, name, args, options);
    }

    // ----------------------------------------------------------------- close

    /** Close the client (client.ts:386-392). */
    public CompletableFuture<Void> close() {
        McpTransport t;
        synchronized (lock) {
            t = transport;
            transport = null;
            disposers.forEach(Runnable::run);
            disposers.clear();
        }
        markClosed(new McpConnectionClosedError());
        if (t == null) {
            return CompletableFuture.completedFuture(null);
        }
        var future = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                t.close();
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    // ------------------------------------------------------ package-private

    @Nullable Pending pendingEntry(long id) {
        synchronized (lock) {
            return pending.get(id);
        }
    }

    void removePendingEntry(long id, Pending entry) {
        synchronized (lock) {
            pending.remove(id);
            var timer = timers.remove(id);
            if (timer != null) {
                timer.cancel(false);
            }
            if (entry.progressToken() != null) {
                progressRequests.remove(entry.progressToken());
            }
            if (entry.signalHandle() != null) {
                entry.signalHandle().run();
            }
        }
    }

    AbortSignal addIncoming(Object rawId) {
        var signal = AbortSignal.create();
        synchronized (lock) {
            incoming.put(rawId, signal);
        }
        return signal;
    }

    @Nullable AbortSignal findIncoming(Object rawId) {
        synchronized (lock) {
            return incoming.get(rawId);
        }
    }

    void removeIncoming(Object rawId) {
        synchronized (lock) {
            incoming.remove(rawId);
        }
    }

    @Nullable McpTransport currentTransport() {
        synchronized (lock) {
            return transport;
        }
    }

    @Nullable Long progressRequest(long token) {
        synchronized (lock) {
            return progressRequests.get(token);
        }
    }

    void rearmTimeout(long id, long timeoutMs) {
        boolean cancellable;
        synchronized (lock) {
            cancellable = pending.get(id).cancellable();
        }
        armTimeout(id, timeoutMs, cancellable);
    }

    void reportError(Throwable error) {
        registrations.reportError(error);
    }

    /** Registrations (same-package inbox access). */
    McpClientRegistrations registrations() {
        return registrations;
    }

    <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpAbortError(error.getMessage() == null ? "MCP request aborted" : error.getMessage());
        } catch (java.util.concurrent.ExecutionException error) {
            var cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error jvmError) {
                throw jvmError;
            }
            throw new RuntimeException(cause);
        }
    }

    // ------------------------------------------------------------ internals

    private void armTimeout(long id, long timeoutMs, boolean cancellable) {
        synchronized (lock) {
            var old = timers.remove(id);
            if (old != null) {
                old.cancel(false);
            }
            if (!Double.isFinite(timeoutMs) || timeoutMs <= 0) {
                return;
            }
            timers.put(id, McpClientSchedules.schedule(timeoutMs, () ->
                    cancelPending(id, new McpTimeoutError(timeoutMs), cancellable, "Request timed out")));
        }
    }

    private void cancelPending(long id, Throwable error, boolean notifyServer) {
        cancelPending(id, error, notifyServer, null);
    }

    private void cancelPending(long id, Throwable error, boolean notifyServer,
                               @Nullable String reason) {
        Pending entry;
        McpTransport t;
        synchronized (lock) {
            entry = pending.get(id);
            if (entry == null) {
                return;
            }
            removePendingEntry(id, entry);
            t = notifyServer ? transport : null;
        }
        entry.future().completeExceptionally(error);
        if (t != null && reason != null) {
            var params = new LinkedHashMap<String, Object>();
            params.put("requestId", id);
            params.put("reason", reason);
            try {
                t.send(McpClientWires.notification("notifications/cancelled", params));
            } catch (Throwable sendError) {
                reportError(sendError);
            }
        }
    }

    /** Reject pending requests, abort served requests, flip to closed (client.ts:590-605). */
    private void markClosed(Throwable error) {
        List<Map.Entry<Long, Pending>> toReject;
        List<AbortSignal> toAbort;
        boolean fireClose;
        synchronized (lock) {
            fireClose = state != State.CLOSED;
            state = State.CLOSED;
            toReject = List.copyOf(pending.entrySet());
            toReject.forEach(e -> removePendingEntry(e.getKey(), e.getValue()));
            toAbort = List.copyOf(incoming.values());
            incoming.clear();
        }
        toReject.forEach(e -> e.getValue().future().completeExceptionally(error));
        toAbort.forEach(AbortSignal::abort);
        if (fireClose) {
            registrations.reportClose();
        }
    }

    private void handleTransportClose() {
        markClosed(new McpConnectionClosedError());
    }

}
