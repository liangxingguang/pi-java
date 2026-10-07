package com.pijava.mcp.oauth;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.jspecify.annotations.Nullable;

/**
 * Loopback HTTP server that receives one authorization response
 * (pi callback.ts:34-163).
 */
public final class OAuthCallbackServer implements AutoCloseable {

    private static final long DEFAULT_TIMEOUT_MS = 5 * 60_000L;

    private final HttpServer server;
    private final String redirectUrl;
    private final List<String> paths;
    private final long timeoutMs;
    private final @Nullable Function<OAuthCallbackPage, String> renderPage;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers =
            Executors.newScheduledThreadPool(0, Thread.ofVirtual().factory());

    private record Pending(CompletableFuture<OAuthCallback> future, @Nullable String path,
                           ScheduledFuture<?> timer) {
    }

    private OAuthCallbackServer(HttpServer server, String redirectUrl, List<String> paths,
                                long timeoutMs,
                                @Nullable Function<OAuthCallbackPage, String> renderPage) {
        this.server = server;
        this.redirectUrl = redirectUrl;
        this.paths = paths;
        this.timeoutMs = timeoutMs;
        this.renderPage = renderPage;
    }

    /** Bind a loopback server and start serving. */
    public static OAuthCallbackServer listen(OAuthCallbackServerOptions options) throws IOException {
        var host = options.host() == null ? "127.0.0.1" : options.host();
        var redirectHost = options.redirectHost() == null ? host : options.redirectHost();
        var path = options.path() == null ? "/callback" : options.path();
        var server = HttpServer.create(new InetSocketAddress(host, options.port()), 0);
        var port = server.getAddress().getPort();
        var redirectUrl = "http://"
                + (redirectHost.contains(":") ? "[" + redirectHost + "]" : redirectHost)
                + ":" + port + path;
        var paths = new ArrayList<String>();
        paths.add(path);
        if (options.extraPaths() != null) {
            paths.addAll(options.extraPaths());
        }
        var instance = new OAuthCallbackServer(server, redirectUrl, List.copyOf(paths),
                options.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : options.timeoutMs(),
                options.renderPage());
        server.createContext("/", instance::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return instance;
    }

    /** The redirect URI this server answers on. */
    public String redirectUrl() {
        return redirectUrl;
    }

    /** Wait for the authorization response carrying {@code state}. */
    public CompletableFuture<OAuthCallback> waitForCallback(String state) {
        return waitForCallback(state, null);
    }

    /**
     * Wait for the authorization response carrying {@code state}. With
     * {@code path}, a response on another path fails, so a server-specific
     * redirect URI can tell authorization servers apart
     * (RFC 9700 section 4.4.2.2).
     */
    public CompletableFuture<OAuthCallback> waitForCallback(String state, @Nullable String path) {
        if (pending.containsKey(state)) {
            throw new IllegalStateException("OAuth state is already pending");
        }
        var future = new CompletableFuture<OAuthCallback>();
        var timer = timers.schedule(() -> {
            pending.remove(state);
            future.completeExceptionally(new IllegalStateException("OAuth callback timed out"));
        }, timeoutMs, TimeUnit.MILLISECONDS);
        var previous = pending.putIfAbsent(state, new Pending(future, path, timer));
        if (previous != null) {
            timer.cancel(false);
            throw new IllegalStateException("OAuth state is already pending");
        }
        return future;
    }

    @Override
    public void close() {
        for (var entry : pending.values()) {
            entry.timer().cancel(false);
            entry.future().completeExceptionally(
                    new IllegalStateException("OAuth callback server closed"));
        }
        pending.clear();
        server.stop(0);
        timers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            dispatch(exchange);
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        var uri = URI.create(exchange.getRequestURI().toString());
        var path = uri.getPath() == null ? "/" : uri.getPath();
        if (!paths.contains(path)) {
            reply(exchange, 404, new OAuthCallbackPage.Failed("Not found", null));
            return;
        }
        var query = OAuthEndpoints.parseForm(uri.getRawQuery());
        var state = query.get("state");
        var entry = state == null ? null : pending.get(state);
        if (entry == null) {
            reply(exchange, 400, new OAuthCallbackPage.Failed("Invalid or expired OAuth state", null));
            return;
        }
        entry.timer().cancel(false);
        pending.remove(state);
        if (entry.path() != null && !path.equals(entry.path())) {
            entry.future().completeExceptionally(new IllegalStateException(
                    "The authorization response arrived on another redirect URI"));
            reply(exchange, 400, new OAuthCallbackPage.Failed("Unexpected redirect URI", null));
            return;
        }
        var error = query.get("error");
        if (error != null) {
            var description = query.get("error_description");
            var message = description != null ? description : error;
            entry.future().completeExceptionally(new IllegalStateException(message));
            reply(exchange, 200, new OAuthCallbackPage.Failed(
                    "Authorization failed. You may close this window.", message));
            return;
        }
        var code = query.get("code");
        if (code == null) {
            entry.future().completeExceptionally(new IllegalStateException(
                    "OAuth callback did not include an authorization code"));
            reply(exchange, 400, new OAuthCallbackPage.Failed("Missing authorization code", null));
            return;
        }
        entry.future().complete(new OAuthCallback(code, state, query.get("iss")));
        reply(exchange, 200, OAuthCallbackPage.ok());
    }

    private void reply(HttpExchange exchange, int status, OAuthCallbackPage page) throws IOException {
        byte[] body;
        String contentType;
        if (renderPage != null) {
            body = renderPage.apply(page).getBytes(StandardCharsets.UTF_8);
            contentType = "text/html; charset=utf-8";
            exchange.getResponseHeaders().set("cache-control", "no-store");
        } else {
            body = plainText(page).getBytes(StandardCharsets.UTF_8);
            contentType = "text/plain; charset=utf-8";
        }
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String plainText(OAuthCallbackPage page) {
        if (page instanceof OAuthCallbackPage.Ok) {
            return "Authorization complete. You may close this window.";
        }
        var failed = (OAuthCallbackPage.Failed) page;
        return failed.details() == null
                ? failed.message()
                : failed.message() + "\n\n" + failed.details();
    }
}
