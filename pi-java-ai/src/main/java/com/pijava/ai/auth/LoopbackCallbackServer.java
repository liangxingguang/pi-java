package com.pijava.ai.auth;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 浏览器 OAuth 登录共享的 loopback 回调服务器（pi {@code auth/oauth/callback-server.ts}）。
 *
 * <p>行为契约（与 pi 逐条对齐）：</p>
 * <ul>
 *   <li>非 GET 或路径不匹配 → 品牌页 404；state 不符 → 400「State mismatch.」（<b>不 settle</b>）；</li>
 *   <li>已 claim/已 settle 的重复回调 → 409；</li>
 *   <li>{@code error} 回调 → 400＋settle 失败；缺 {@code code} → 400（不 settle）；</li>
 *   <li>首个合法回调置 claimed，在<b>发页面之前</b>跑 {@link Options.Completer}（交换失败可见为 502）；</li>
 *   <li>{@link #cancel()} 只在未 claim 时把等待结束为 {@code null}；已 claim 不抢；</li>
 *   <li>端口绑定失败（{@link java.net.BindException}）原样抛，调用方自行决定回落或硬失败；</li>
 *   <li>{@link #awaitCallbackOrManual} 复刻 pi 的「回调 vs 手工粘贴」竞速。</li>
 * </ul>
 */
final class LoopbackCallbackServer<T> {

    /** 启动选项（字段语义同 pi {@code OAuthCallbackServerOptions}）。 */
    record Options<T>(
        String providerName,
        String host,
        int port,
        String path,
        /** redirectUri 中的主机名（与监听 host 不同时给，如 {@code localhost}）；null 用 host。 */
        String redirectHost,
        /** 期望的 state；null 表示该 provider 不发 state，不校验。 */
        String expectedState,
        /** 整体等待超时；null 不超时。 */
        Duration timeout,
        Completer<T> complete
    ) {
        /** 收到 code 后、发页面之前执行（通常是 token 交换）；抛错 → 502 页面＋等待失败。 */
        @FunctionalInterface
        interface Completer<T> {
            T complete(String code, Map<String, String> query) throws Exception;
        }
    }

    /** 竞速结果：浏览器回调值，或手工粘贴的原始输入（由各流自行解析）。 */
    sealed interface Outcome<T> permits Outcome.Callback, Outcome.Manual {
        record Callback<T>(T value) implements Outcome<T> {}
        record Manual<T>(String input) implements Outcome<T> {}
    }

    private final Options<T> options;
    private final HttpServer server;
    private final CompletableFuture<T> outcome = new CompletableFuture<>();
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicBoolean settled = new AtomicBoolean();
    private final Thread timeoutThread;

    private LoopbackCallbackServer(Options<T> options) throws IOException {
        this.options = options;
        // create() 即绑定：端口被占在此抛 BindException（不挑端口，由调用方决定回落/硬失败）。
        this.server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName(options.host()), options.port()), 0);
        // 单一 context 自己做精确路径与方法分派（野请求也要回品牌页 404）。
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        if (options.timeout() != null) {
            this.timeoutThread = Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(options.timeout().toMillis());
                    finishError(new IOException(options.providerName() + " sign-in timed out"));
                } catch (InterruptedException e) {
                    // settle 后取消超时，正常路径。
                }
            });
        } else {
            this.timeoutThread = null;
        }
    }

    /**
     * 绑定并启动；端口被占抛 {@link java.net.BindException}（不挑端口，同 pi）。
     */
    static <T> LoopbackCallbackServer<T> start(Options<T> options) throws IOException {
        return new LoopbackCallbackServer<>(options);
    }

    /** 拼 redirectUri：{@code http://<redirectHost??host>:<实际端口><path>}，IPv6 加方括号。 */
    String redirectUri() {
        var host = options.redirectHost() != null ? options.redirectHost() : options.host();
        var bracketed = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + bracketed + ":" + server.getAddress().getPort() + options.path();
    }

    /** 等待结果：complete 成功得值；cancel 得 null；error/异常/超时异常完成。 */
    CompletableFuture<T> outcome() {
        return outcome;
    }

    /** 停止等待浏览器（未 claim 时）；已 claim 的回调照常完成（pi {@code cancel}）。 */
    void cancel() {
        if (!claimed.get()) {
            finishValue(null);
        }
    }

    /** 关掉服务器；未 settle 的等待以 closed 异常结束。 */
    void close() {
        finishError(new IOException("OAuth callback server closed"));
        // stop(0) 关闭监听并关闭全部连接（含空闲 keep-alive），等价 Node close()+closeAllConnections()。
        server.stop(0);
    }

    // ── 请求分派 ────────────────────────────────────────────────

    private void handle(HttpExchange exchange) {
        try (exchange) {
            var uri = URI.create(exchange.getRequestURI().toString());
            var query = parseQuery(uri.getRawQuery());
            if (!"GET".equals(exchange.getRequestMethod()) || !uri.getPath().equals(options.path())) {
                sendPage(exchange, 404, OAuthPages.error("Callback route not found."));
                return;
            }
            if (options.expectedState() != null
                && !options.expectedState().equals(query.get("state"))) {
                sendPage(exchange, 400, OAuthPages.error("State mismatch."));
                return;
            }
            if (claimed.get() || settled.get()) {
                sendPage(exchange, 409, OAuthPages.error("This sign-in has already been handled."));
                return;
            }
            var error = query.get("error");
            if (error != null) {
                var description = query.getOrDefault("error_description", error);
                sendPage(exchange, 400,
                    OAuthPages.error(options.providerName() + " authorization failed.", description));
                finishError(new IOException(
                    options.providerName() + " authorization failed: " + description));
                return;
            }
            var code = query.get("code");
            if (code == null) {
                sendPage(exchange, 400, OAuthPages.error("Missing authorization code."));
                return;
            }
            claimed.set(true);
            try {
                var value = options.complete().complete(code, query);
                sendPage(exchange, 200, OAuthPages.success(
                    "Signed in to " + options.providerName() + ". You may now close this page."));
                finishValue(value);
            } catch (Exception e) {
                var message = e.getMessage() == null ? e.toString() : e.getMessage();
                sendPage(exchange, 502,
                    OAuthPages.error(options.providerName() + " sign-in failed.", message));
                finishError(e instanceof IOException ioe ? ioe : new IOException(message, e));
            }
        } catch (IOException e) {
            finishError(e);
        }
    }

    private void finishValue(T value) {
        if (settled.compareAndSet(false, true)) {
            if (timeoutThread != null) {
                timeoutThread.interrupt();
            }
            outcome.complete(value);
        }
    }

    private void finishError(Throwable error) {
        if (settled.compareAndSet(false, true)) {
            if (timeoutThread != null) {
                timeoutThread.interrupt();
            }
            outcome.completeExceptionally(error);
        }
    }

    private static void sendPage(HttpExchange exchange, int status, String html) throws IOException {
        var body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("cache-control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        var params = new HashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (var pair : rawQuery.split("&")) {
            var idx = pair.indexOf('=');
            var key = idx < 0 ? pair : pair.substring(0, idx);
            var value = idx < 0 ? "" : pair.substring(idx + 1);
            if (!key.isEmpty()) {
                params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    // ── 回调/手工竞速（pi waitForCallbackOrManualInput） ─────────

    /**
     * 回调服务器与手工粘贴竞速。{@code server} 为 null 时只用手工提示。
     *
     * <p>手工提示（非空白成功或失败）一落地就 {@link #cancel()}；回调先到非 null 得
     * {@link Outcome.Callback}；服务器被取消（手工先落地）则取手工输入得
     * {@link Outcome.Manual}；手工先失败时即使回调后到也按 pi 顺序抛手工错误。</p>
     *
     * <p><b>与 pi 的环境差</b>：无控制台时 {@link OAuthInteraction#prompt} 立即返回空串
     * （pi 的替身是「永不 settle 的 pending prompt」）。空白输入不参与竞速、不取消服务器，
     * 登录继续等浏览器回调，否则无桌面/无控制台环境会在回调到达前自败。</p>
     */
    static <T> Outcome<T> awaitCallbackOrManual(OAuthInteraction interaction,
                                                LoopbackCallbackServer<T> server,
                                                String promptMessage) throws IOException {
        var manual = new CompletableFuture<String>();
        var promptThread = Thread.startVirtualThread(() -> {
            String input;
            try {
                input = interaction.prompt(promptMessage);
            } catch (RuntimeException e) {
                manual.completeExceptionally(e);
                return;
            }
            // 空白＝没有手工输入能力/用户未贴：不 settle，把竞速让给回调。
            if (input != null && !input.isBlank()) {
                manual.complete(input);
            }
        });
        // pi：manual settle（无论成败）即取消回调等待。
        manual.whenComplete((input, error) -> {
            if (server != null) {
                server.cancel();
            }
        });
        try {
            if (server == null) {
                return new Outcome.Manual<>(join(manual));
            }
            var value = join(server.outcome());
            if (manual.isCompletedExceptionally()) {
                join(manual); // 必抛：传播手工错误（即使回调已到，照 pi 顺序手工优先）。
            }
            if (value != null) {
                return new Outcome.Callback<>(value);
            }
            return new Outcome.Manual<>(join(manual));
        } finally {
            // pi：settle 后 abort manual prompt。控制台 readLine 不可中断，线程随之自然消亡。
            promptThread.interrupt();
        }
    }

    private static <R> R join(CompletableFuture<R> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException e) {
            var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(cause.getMessage(), cause);
        }
    }
}
