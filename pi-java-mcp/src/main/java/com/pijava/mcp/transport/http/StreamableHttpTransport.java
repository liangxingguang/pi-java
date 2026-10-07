package com.pijava.mcp.transport.http;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;
import com.pijava.mcp.transport.AbstractMcpTransport;

/**
 * MCP transport over Streamable HTTP (streamable-http.ts:183-274).
 *
 * <p>Each request is a POST carrying one JSON-RPC message; the server answers
 * with JSON (possibly a batch array), an SSE stream, or an acknowledgement.
 * Sessions are tracked via the {@code Mcp-Session-Id} header.</p>
 */
final class StreamableHttpTransport extends AbstractMcpTransport {

    private static final String ACCEPT = "application/json, text/event-stream";
    private static final int ERROR_BODY_BYTES = 8 * 1024;

    static final ScheduledExecutorService TIMEOUTS =
            Executors.newScheduledThreadPool(0, Thread.ofVirtual().factory());

    private final StreamableHttpTransportOptions options;
    private final McpHttpFetch fetch;
    private final AbortSignal abort = AbortSignal.create();
    private final StreamableHttpStreams streams;
    private volatile boolean started;
    private volatile boolean closed;
    private @Nullable String sessionId;
    private @Nullable String protocolVersion;
    private boolean getStreamStarted;

    /** Create a transport. */
    StreamableHttpTransport(StreamableHttpTransportOptions options) {
        this.options = options;
        this.fetch = options.fetch() == null ? new JdkMcpHttpFetch() : options.fetch();
        this.streams = new StreamableHttpStreams(this);
    }

    /** Assigned session id, if any. */
    @Nullable String sessionId() {
        return sessionId;
    }

    /** Negotiated protocol version, if downgraded. */
    @Nullable String protocolVersion() {
        return protocolVersion;
    }

    AbortSignal abortSignal() {
        return abort;
    }

    @Override
    public void start() {
        if (started) {
            throw new IllegalStateException("MCP Streamable HTTP transport already started");
        }
        if (closed) {
            throw new McpConnectionClosedError();
        }
        started = true;
    }

    @Override
    public void setProtocolVersion(String version) {
        this.protocolVersion = version;
    }

    @Override
    public void send(Map<String, Object> message) throws Exception {
        if (!started || closed) {
            throw new McpConnectionClosedError();
        }
        var body = com.pijava.mcp.McpJson.mapper().writeValueAsBytes(message);
        var baseHeaders = Map.of("Accept", ACCEPT, "Content-Type", "application/json");
        var response = authorizedFetch("POST", baseHeaders, body);
        checkResponse(response);
        captureSession(response);

        if (!isRequest(message)) {
            discard(response);
            if ("notifications/initialized".equals(message.get("method"))) {
                startGetStream();
            }
            return;
        }

        if (response.status() == 202 || response.status() == 204) {
            discard(response);
            throw new McpHttpError(response.status(),
                    "MCP server accepted request " + message.get("method") + " without a response");
        }
        var type = response.contentType();
        if ("application/json".equals(type)) {
            var parsed = com.pijava.mcp.McpJson.mapper().readValue(response.body(), Object.class);
            var items = parsed instanceof List<?> list ? list : List.of(parsed);
            for (var item : items) {
                emitMessage(item);
            }
            return;
        }
        if ("text/event-stream".equals(type)) {
            var requestId = message.get("id");
            Thread.startVirtualThread(() -> streams.consumeResponseStream(response.body(), requestId));
            return;
        }
        discard(response);
        throw new McpHttpError(response.status(),
                "Unsupported MCP response content type: " + type);
    }

    /** GET/POST with auth headers and one challenge-driven retry. */
    McpHttpResponse authorizedFetch(String method, Map<String, String> baseHeaders,
                                    byte @Nullable [] body) throws Exception {
        var auth = options.authProvider();
        for (var attempt = 0; ; attempt++) {
            var headers = buildHeaders(baseHeaders);
            var request = new McpHttpRequest(method, options.url(), headers, body);
            var response = fetch.execute(request, abort);
            if (attempt > 0 || auth == null || !needsAuthorization(response)) {
                return response;
            }
            var challenge = response.headers().get("WWW-Authenticate");
            auth.onUnauthorized(new AuthProvider.Context(
                    response.status(), challenge == null ? "" : challenge,
                    options.url(), null));
            discard(response);
        }
    }

    private Map<String, String> buildHeaders(Map<String, String> extra) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        if (options.headers() != null) {
            headers.putAll(options.headers());
        }
        headers.putAll(extra);
        if (sessionId != null) {
            headers.put("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            headers.put("MCP-Protocol-Version", protocolVersion);
        }
        var auth = options.authProvider();
        if (auth != null && auth.token() != null) {
            headers.put("Authorization", "Bearer " + auth.token());
        }
        return headers;
    }

    void checkResponsePublic(McpHttpResponse response) throws IOException {
        checkResponse(response);
    }

    void captureSessionPublic(McpHttpResponse response) {
        captureSession(response);
    }

    private void checkResponse(McpHttpResponse response) throws IOException {
        if (response.status() >= 200 && response.status() < 300) {
            return;
        }
        var body = readErrorBody(response);
        if (response.status() == 401) {
            var challenge = response.headers().get("WWW-Authenticate");
            throw new McpAuthRequiredError(body, challenge);
        }
        if (response.status() == 404 && sessionId != null) {
            throw new McpSessionExpiredError(body);
        }
        throw new McpHttpError(response.status(),
                describeHttpFailure(response.status(), body), body);
    }

    private static String readErrorBody(McpHttpResponse response) {
        try {
            var bytes = response.body().readNBytes(ERROR_BODY_BYTES);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }

    private static String describeHttpFailure(int status, String body) {
        var text = body.trim();
        var snippet = text.length() > 500 ? text.substring(0, 497) + "..." : text;
        return "MCP HTTP request failed with status " + status
                + (snippet.isEmpty() ? "" : ": " + snippet);
    }

    private void captureSession(McpHttpResponse response) {
        var assigned = response.headers().get("Mcp-Session-Id");
        if (assigned != null) {
            sessionId = assigned;
        }
    }

    private void startGetStream() {
        if (!options.openGetStream() || getStreamStarted || closed) {
            return;
        }
        getStreamStarted = true;
        Thread.startVirtualThread(streams::runGetStream);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        abort.abort();
        if (started && sessionId != null) {
            deleteSession();
        }
        emitClose();
    }

    /** Best-effort session DELETE with a 1-second timeout (streamable-http.ts:257-270). */
    private void deleteSession() {
        var deleteSignal = AbortSignal.create();
        var future = TIMEOUTS.schedule(deleteSignal::abort, 1, TimeUnit.SECONDS);
        try {
            var headers = buildHeaders(Map.of());
            var request = new McpHttpRequest("DELETE", options.url(), headers);
            fetch.execute(request, deleteSignal);
        } catch (Exception ignored) {
        } finally {
            future.cancel(false);
        }
    }

    /** 401, or 403 carrying an insufficient_scope challenge. */
    private static boolean needsAuthorization(McpHttpResponse response) {
        if (response.status() == 401) {
            return true;
        }
        if (response.status() != 403) {
            return false;
        }
        var challenge = response.headers().get("WWW-Authenticate");
        return challenge != null
                && java.util.regex.Pattern.compile(
                        "(?:^|[\\s,])error=\"?insufficient_scope\"?",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(challenge).find();
    }

    private static boolean isRequest(Map<String, Object> message) {
        return message.containsKey("id") && message.containsKey("method");
    }

    private static void discard(McpHttpResponse response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
        }
    }

    // Accessors for StreamableHttpStreams.

    StreamableHttpTransportOptions transportOptions() {
        return options;
    }

    int maxMessageBytes() {
        return options.maxMessageBytes() > 0
                ? options.maxMessageBytes()
                : com.pijava.mcp.transport.AbstractMcpTransport.DEFAULT_MAX_MESSAGE_BYTES;
    }

    /** Emit an inbound JSON-RPC message to transport listeners. */
    void deliver(Object message) {
        emitMessage(message);
    }

    /** Report a stream error. */
    void fail(Throwable error) {
        emitError(error);
    }

    boolean isClosed() {
        return closed;
    }
}
