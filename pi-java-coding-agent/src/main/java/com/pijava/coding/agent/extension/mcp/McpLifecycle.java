package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.runtime.ConfigValueResolver;
import com.pijava.mcp.runtime.McpConnectionOptions;
import com.pijava.mcp.runtime.McpDefaultTransport;
import com.pijava.mcp.runtime.McpOAuthCredentialStore;
import com.pijava.mcp.runtime.McpServerConnection;
import com.pijava.mcp.runtime.McpServerLog;
import com.pijava.mcp.runtime.McpServerState;

/**
 * 批 24（docs/35 T4/T5/T7）：连接生命周期（pi {@code index.ts} 的
 * startConnection/createConnection/waitForServers/waitForDirectServers/
 * reportProblems/tokensAtSignIn/reconnectSignedIn 逐字对位）。
 *
 * <p>异步模型（裁决⑩）：pi 的 Promise/事件循环 ⇒ 虚拟线程＋{@link CompletableFuture}；
 * pi 的 {@code await} ⇒ 在事件处理器内同步 join；pi 的 {@code ctx.signal} 无对位，
 * 等待只随会话 shutdown 经 {@link McpSessionScope#abortWatch()} 解封（窄差 D-new1）。</p>
 */
final class McpLifecycle {

    private final McpExtensionState state;
    private final McpExtension extension;
    private final Executor executor;
    /** B188（裁决⑫）：两段式壳注入的解析器（settings.shellPath 优先、ENOENT 回落）。 */
    private final ConfigValueResolver resolver;

    /** pi {@code index.ts:530}：等登录的服务器在 needs-auth 时的令牌快照。 */
    private final Map<McpServerConnection, String> tokensAtSignIn = new java.util.LinkedHashMap<>();

    private volatile @Nullable McpOAuthCredentialStore credentials;
    private volatile @Nullable McpServerLog serverLog;

    McpLifecycle(McpExtensionState state, McpExtension extension, Executor executor,
                 ConfigValueResolver resolver) {
        this.state = state;
        this.extension = extension;
        this.executor = executor;
        this.resolver = resolver;
    }

    // ── 惰性单例（pi getCredentials/getServerLog :375-381） ──

    private java.nio.file.Path agentDir() {
        // session_start 必先行解析；防御回落（--help 轻量道等无装配路径）
        var dir = state.agentDir;
        return dir != null ? dir
            : com.pijava.coding.agent.core.FileSettingsStorage.defaultAgentDir();
    }

    McpOAuthCredentialStore credentials() {
        var current = credentials;
        if (current == null) {
            current = new McpOAuthCredentialStore(agentDir());
            credentials = current;
        }
        return current;
    }

    McpServerLog serverLog() {
        var current = serverLog;
        if (current == null) {
            current = new McpServerLog(agentDir().resolve("mcp.log"));
            serverLog = current;
        }
        return current;
    }

    // ── 连接创建与启动（pi :563-611） ──

    /** 建连接；回调全部经 isCurrent 闸（迟到结果丢弃）。 */
    private @Nullable McpServerConnection createConnection(McpServerHandle server,
                                                           BooleanSupplier isCurrent) {
        if (!isCurrent.getAsBoolean()) {
            return null;
        }
        var transportFactory = extension.transportFactory();
        var connection = new McpServerConnection(new McpConnectionOptions(
            server.entry, state.sessionCwd,
            transportFactory != null ? transportFactory
                : (entry, cwd, authProvider) ->
                    McpDefaultTransport.create(entry, cwd, authProvider, resolver),
            credentials(),
            // B189（裁决⑫）：auth.provider 的令牌源——pi modelRegistry.getApiKeyForProvider
            // 对位（OAuth 凭证优先，回落实体 api key；每请求现读，pi 同）。
            provider -> {
                var oauth = new OAuthCredentialStore().resolve(provider);
                if (oauth.isPresent()) {
                    return oauth.get().accessToken();
                }
                return new FileCredentialStore().resolveApiKey(provider).orElse(null);
            },
            conn -> {
                if (isCurrent.getAsBoolean()) {
                    extension.registrar().registerTools(conn);
                }
            },
            conn -> {
                if (isCurrent.getAsBoolean()) {
                    onConnectionChange(conn);
                } else {
                    tokensAtSignIn.remove(conn);
                }
            },
            serverLog(), "pi-java", Version.VERSION, resolver));
        server.connection = connection;
        state.emitChange();
        return connection;
    }

    /**
     * 后台连接（pi {@code startConnection} :595-611）：{@code after} 落地后开工；
     * disabled/替换/shutdown 即不启动。{@code server.ready} 永不失败完成。
     */
    CompletableFuture<Void> startConnection(McpServerHandle server,
                                            @Nullable CompletableFuture<?> after) {
        var scope = extension.scope();
        var attempt = new Object();
        server.attempt = attempt;
        BooleanSupplier isCurrent = () -> !scope.isAborted() && server.attempt == attempt
            && McpExtensionState.isEnabled(server) && state.servers.contains(server);
        var started = CompletableFuture.runAsync(() -> {
            if (!isCurrent.getAsBoolean()) {
                return;
            }
            var connection = createConnection(server, isCurrent);
            if (connection == null || !isCurrent.getAsBoolean()) {
                return;
            }
            try {
                connection.getClient().join();
            } catch (RuntimeException ignored) {
                // 失败落在连接状态里（pi .catch(() => undefined) 对位）
            }
        }, executor);
        var ready = after != null ? after.thenCompose(ignored -> started) : started;
        server.ready = ready.handle((ok, err) -> null);
        return ready;
    }

    // ── 等待（pi :614-637 / :1156-1173） ──

    /** 等最新一轮连接尝试（等待中换代的继续等），abort 即返。 */
    void waitForServers(List<McpServerHandle> waiting) {
        var scope = extension.scope();
        for (;;) {
            var ready = new ArrayList<Map.Entry<McpServerHandle, CompletableFuture<Void>>>();
            for (var server : waiting) {
                if (McpExtensionState.isEnabled(server) && state.servers.contains(server)
                    && server.ready != null) {
                    ready.add(Map.entry(server, server.ready));
                }
            }
            if (ready.isEmpty() || scope.isAborted()) {
                return;
            }
            var all = CompletableFuture.allOf(ready.stream()
                .map(Map.Entry::getValue).toArray(CompletableFuture[]::new));
            CompletableFuture.anyOf(all, scope.abortWatch()).join();
            if (scope.isAborted()) {
                return;
            }
            var settled = true;
            for (var entry : ready) {
                var server = entry.getKey();
                if (McpExtensionState.isEnabled(server) && state.servers.contains(server)
                    && server.ready != entry.getValue()) {
                    settled = false;
                    break;
                }
            }
            if (settled) {
                return;
            }
        }
    }

    /**
     * 首提示等 direct 服务器（pi {@code waitForDirectServers} :1156-1173）——
     * 有界等待（默认 10s），超时放行＋通知（文案逐字）。
     */
    void waitForDirectServers(ExtensionContext ctx) {
        if (state.waitedForStartup) {
            return;
        }
        state.waitedForStartup = true;
        var ready = state.servers.stream()
            .filter(server -> McpExtensionState.isEnabled(server)
                && McpExtensionState.hasDirectTools(server.entry) && server.ready != null)
            .map(server -> server.ready)
            .toArray(CompletableFuture[]::new);
        if (ready.length == 0) {
            return;
        }
        var all = CompletableFuture.allOf(ready);
        try {
            CompletableFuture.anyOf(all, extension.scope().abortWatch())
                .get(state.startupWaitMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            ctx.ui().notify(
                "MCP servers are still connecting; their tools become available once connected.",
                NotifyType.INFO);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException impossible) {
            // ready 链永不失败完成
        }
    }

    // ── 报告（pi :640-655） ──

    /** 启动后需用户照顾的一切拼一条通知；{@code only} 非空时只报连上的那批。 */
    void reportProblems(ExtensionContext ctx) {
        reportProblems(ctx, null);
    }

    void reportProblems(ExtensionContext ctx, @Nullable List<McpServerHandle> only) {
        var lines = new ArrayList<String>();
        if (only == null) {
            for (var error : state.configErrors) {
                lines.add("config: " + error);
            }
        }
        for (var server : only != null ? only : state.servers) {
            var connection = server.connection;
            var connectionState = connection == null ? null : connection.state();
            if (connectionState == McpServerState.NEEDS_AUTH
                || connectionState == McpServerState.FAILED) {
                lines.add(server.entry.name() + ": " + McpExtensionState.describeState(server));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        var body = new StringBuilder("MCP servers need attention:\n");
        for (var line : lines) {
            body.append("  ").append(line).append('\n');
        }
        body.append("Run /mcp to fix.");
        ctx.ui().notify(body.toString(), NotifyType.WARNING);
    }

    // ── 不可达警告（裁决⑥：pi ensureDiscoveryActive :500-534 的 java 替身） ──

    /**
     * java 无 codemode/tool_search ⇒ 间接 exposure 的工具永不可达（pi 的
     * 「neither is active」警告在 java 是必然态）。检测到即一次性警告
     * （每 session_start 复位）；文案 java 化（窄差 D-new4）。
     */
    void warnUnreachableOnce(ExtensionContext ctx) {
        if (state.warnedUnreachable) {
            return;
        }
        var indirect = state.servers.stream().anyMatch(server ->
            McpExtensionState.isEnabled(server)
                && McpExtensionState.hasIndirectTools(server.entry));
        if (!indirect) {
            return;
        }
        state.warnedUnreachable = true;
        ctx.ui().notify("MCP servers with \"codemode\"/\"deferred\" exposure are unreachable:"
            + " pi-java has no codemode or tool_search tool; set \"exposure\": \"direct\""
            + " (per server or per tool) to declare their tools to the model.", NotifyType.WARNING);
    }

    // ── 进程外登录拾取（pi :536-560） ──

    private void onConnectionChange(McpServerConnection connection) {
        if (connection.state() != McpServerState.NEEDS_AUTH) {
            tokensAtSignIn.remove(connection);
        } else {
            tokensAtSignIn.putIfAbsent(connection, storedTokens(connection));
        }
        state.emitChange();
    }

    private String storedTokens(McpServerConnection connection) {
        var url = connection.oauthUrl();
        if (url == null) {
            return "null";
        }
        try {
            return extension.json().writeValueAsString(
                credentials().tokens(connection.name(), url.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            return "null";
        }
    }

    /** {@code turn_start}：凭证落盘变化 ⇒ 重连（pi {@code reconnectSignedIn} :548-560）。 */
    void reconnectSignedIn(ExtensionContext ctx) {
        var signedIn = new ArrayList<McpServerConnection>();
        synchronized (tokensAtSignIn) {
            for (var entry : tokensAtSignIn.entrySet()) {
                if (!storedTokens(entry.getKey()).equals(entry.getValue())) {
                    signedIn.add(entry.getKey());
                }
            }
            for (var connection : signedIn) {
                tokensAtSignIn.remove(connection);
            }
        }
        if (signedIn.isEmpty()) {
            return;
        }
        for (var connection : signedIn) {
            try {
                connection.reconnect().join();
            } catch (RuntimeException ignored) {
                // pi Promise.allSettled——单服务器失败不挡其余
            }
        }
        warnUnreachableOnce(ctx);
    }

    boolean hasPendingSignIns() {
        return !tokensAtSignIn.isEmpty();
    }

    /** {@code /mcp} 子命令的 {@code await pending}（pi :1298/:1302）。 */
    void awaitPending() {
        var pending = state.pending;
        if (pending != null) {
            pending.join();
        }
    }

    static Executor virtualThreads() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }
}
