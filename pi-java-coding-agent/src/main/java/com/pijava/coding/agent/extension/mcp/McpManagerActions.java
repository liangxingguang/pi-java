package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionCustomOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.runtime.McpServerConnection;
import com.pijava.mcp.runtime.McpServerState;
import com.pijava.mcp.runtime.McpSignIn;
import com.pijava.mcp.runtime.McpSignInCancelledError;
import com.pijava.mcp.runtime.McpSignInPrompt;

/**
 * 批 24（docs/35 T9）：/mcp 命令与管理动作（pi {@code index.ts} 的
 * signIn/signOut/reconnect/setEnabled/setExposure/saveConfig :656-787、
 * 管理循环 runInBackground/runAction/manage :937-1010、
 * 子命令面 formatStatus/pickServer/loginCommand/handler :1014-1343 逐字对位）。
 */
final class McpManagerActions {

    /** pi {@code MCP_USAGE}（index.ts:280 逐字）。 */
    static final String MCP_USAGE =
        "Usage: /mcp, /mcp login [server], /mcp logout [server], /mcp reconnect [server]";

    private final McpExtensionState state;
    private final ExtensionContext ctx;
    private final McpLifecycle lifecycle;
    private final McpServerOps ops;
    private final Executor executor;
    private final Supplier<McpSessionScope> scope;

    McpManagerActions(McpExtensionState state, ExtensionContext ctx, McpLifecycle lifecycle,
                      McpToolRegistrar registrar, Executor executor,
                      Supplier<McpSessionScope> scope) {
        this.state = state;
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.ops = new McpServerOps(state, lifecycle, registrar);
        this.executor = executor;
        this.scope = scope;
    }

    // ── /mcp 命令入口（pi handler :1290-1343） ──

    void runCommand(String args) {
        var parts = args.trim().split("\\s+");
        var tokens = java.util.Arrays.stream(parts).filter(s -> !s.isEmpty()).toList();
        if (tokens.isEmpty()) {
            if ("tui".equals(ctx.mode())) {
                showMcpManager(ui -> manage(ui));
            } else {
                lifecycle.awaitPending();
                ctx.ui().notify(formatStatus(), NotifyType.INFO);
            }
            return;
        }
        if (tokens.size() > 2) {
            ctx.ui().notify(MCP_USAGE, NotifyType.WARNING);
            return;
        }
        var action = tokens.get(0);
        var name = tokens.size() > 1 ? tokens.get(1) : null;
        lifecycle.awaitPending();
        switch (action) {
            case "login" -> {
                var server = pickServer(name, McpManagerActions::usesOAuth,
                    s -> s.connection != null
                        && s.connection.state() == McpServerState.NEEDS_AUTH,
                    "No enabled MCP server uses OAuth. Only HTTP servers without an"
                        + " Authorization header do.");
                if (server != null) {
                    loginCommand(server);
                }
            }
            case "logout" -> {
                var server = pickServer(name, McpManagerActions::usesOAuth,
                    s -> s.connection != null
                        && s.connection.state() == McpServerState.NEEDS_AUTH,
                    "No enabled MCP server uses OAuth. Only HTTP servers without an"
                        + " Authorization header do.");
                if (server == null) {
                    return;
                }
                var removed = signOut(server);
                ctx.ui().notify(removed
                    ? "Signed out of MCP server \"" + server.entry.name() + "\"."
                    : "No stored credentials for MCP server \"" + server.entry.name() + "\".",
                    NotifyType.INFO);
            }
            case "reconnect" -> {
                var server = pickServer(name, s -> s.connection != null,
                    s -> s.connection != null
                        && (s.connection.state() == McpServerState.FAILED
                            || s.connection.state() == McpServerState.DISCONNECTED),
                    "No enabled MCP server to reconnect.");
                if (server == null) {
                    return;
                }
                var failure = ops.reconnect(server);
                if (failure != null) {
                    ctx.ui().notify(failure, NotifyType.ERROR);
                } else {
                    lifecycle.warnUnreachableOnce(ctx);
                    ctx.ui().notify("Reconnected to MCP server \"" + server.entry.name()
                        + "\" (" + McpExtensionState.describeState(server) + ").",
                        NotifyType.INFO);
                }
            }
            default -> ctx.ui().notify(MCP_USAGE, NotifyType.WARNING);
        }
    }

    // ── 非 TUI 状态（pi formatStatus :1014-1036 逐字） ──

    String formatStatus() {
        if (state.servers.isEmpty() && state.configErrors.isEmpty()
            && state.overridden.isEmpty()) {
            return "No MCP servers configured. Add them to "
                + state.agentDirOrDefault().resolve("mcp.json") + " or .pi-java/mcp.json.";
        }
        var lines = new ArrayList<String>();
        for (var server : state.servers) {
            var name = server.entry.name();
            var exposure = McpExtensionState.exposureOf(server.entry).wire();
            var connection = server.connection;
            if (connection != null && connection.state() == McpServerState.NEEDS_AUTH) {
                lines.add(name + ": needs sign-in, run /mcp login " + name
                    + " (" + exposure + ")");
                continue;
            }
            var tools = connection != null
                && connection.state() == McpServerState.CONNECTED
                    ? ", " + connection.tools().size() + " tools" : "";
            String serverState;
            if (!McpExtensionState.isEnabled(server)) {
                serverState = "disabled";
            } else if (connection != null
                && connection.state() == McpServerState.DISCONNECTED) {
                serverState = "disconnected, reconnects on next call";
            } else {
                serverState = connection != null ? connection.state().wire() : "starting";
            }
            var error = connection != null && connection.error() != null
                && connection.state() != McpServerState.CONNECTED
                    ? "\n    " + connection.error().replace("\n", "\n    ") : "";
            lines.add(name + ": " + serverState + tools + " (" + exposure + ")" + error);
        }
        for (var error : state.configErrors) {
            lines.add("config error: " + error);
        }
        for (var line : state.overridden) {
            lines.add("overridden: " + line);
        }
        return String.join("\n", lines);
    }

    // ── pickServer（pi :1039-1064 逐字） ──

    private @Nullable McpServerHandle pickServer(@Nullable String name,
                                                 Predicate<McpServerHandle> eligible,
                                                 Predicate<McpServerHandle> preferred,
                                                 String none) {
        if (name != null) {
            var server = state.findServer(name);
            if (server == null) {
                ctx.ui().notify("No MCP server named \"" + name + "\".", NotifyType.ERROR);
                return null;
            }
            if (!eligible.test(server)) {
                ctx.ui().notify(none, NotifyType.ERROR);
                return null;
            }
            return server;
        }
        var candidates = state.servers.stream().filter(eligible).toList();
        if (candidates.isEmpty()) {
            ctx.ui().notify(none, NotifyType.INFO);
            return null;
        }
        var preferredCandidates = candidates.stream().filter(preferred).toList();
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (preferredCandidates.size() == 1) {
            return preferredCandidates.get(0);
        }
        var choice = ctx.ui().select("MCP server",
            candidates.stream().map(s -> s.entry.name()).toList(), null);
        if (choice == null) {
            return null;
        }
        return candidates.stream()
            .filter(s -> s.entry.name().equals(choice)).findFirst().orElse(null);
    }

    private static boolean usesOAuth(McpServerHandle server) {
        return server.connection != null && server.connection.oauthUrl() != null;
    }

    // ── 登录（pi loginCommand :1086-1109 ＋ signInWithUi :937-956 ＋ signIn :658-706） ──

    private void loginCommand(McpServerHandle server) {
        var name = server.entry.name();
        if (!ctx.hasUi()) {
            ctx.ui().notify("Signing in to MCP server \"" + name
                + "\" requires interactive mode.", NotifyType.ERROR);
            return;
        }
        String failure;
        if ("tui".equals(ctx.mode())) {
            var result = new AtomicReference<String>();
            showMcpManager(ui -> result.set(signInWithUi(ui, server)));
            failure = result.get();
        } else {
            failure = signIn(server, new McpSignInPrompt() {
                @Override
                public void showAuthorizationUrl(java.net.URI url) {
                    ctx.ui().notify("Sign in to MCP server \"" + name
                        + "\" in your browser:\n" + url, NotifyType.INFO);
                    openUrl(url.toString());
                }

                @Override
                public @Nullable String promptForRedirectUrl(AbortSignal signal) {
                    // 窄差 D-new3：ExtensionUI.input 无 abort 槽（pi 的 {signal} 不移植）
                    return ctx.ui().input("Waiting for sign-in to \"" + name
                        + "\". If the browser cannot reach this machine, paste the URL"
                        + " it was redirected to.", "http://127.0.0.1:.../callback?code=...",
                        null);
                }
            });
        }
        if (scope.get().isAborted()) {
            return;
        }
        if (failure != null) {
            ctx.ui().notify(failure,
                "Sign-in cancelled.".equals(failure) ? NotifyType.INFO : NotifyType.ERROR);
            return;
        }
        lifecycle.warnUnreachableOnce(ctx);
        ctx.ui().notify("Signed in to MCP server \"" + name + "\" ("
            + (server.connection != null ? server.connection.tools().size() : 0)
            + " tools).", NotifyType.INFO);
    }

    /** 管理器内登录（status 屏＋redirect 屏）。 */
    private @Nullable String signInWithUi(McpManagerUi ui, McpServerHandle server) {
        var title = "Sign in to " + server.entry.name();
        var cancel = AbortSignal.create();
        Consumer<String> status = message ->
            ui.status(title, message, cancel::abort);
        var authorizationUrl = new AtomicReference<>("");
        status.accept("Contacting the authorization server…");
        return signIn(server, new McpSignInPrompt() {
            @Override
            public void showAuthorizationUrl(java.net.URI url) {
                authorizationUrl.set(url.toString());
                openUrl(url.toString());
            }

            @Override
            public @Nullable String promptForRedirectUrl(AbortSignal signal) {
                var value = ui.redirectUrl(title, authorizationUrl.get(), signal).join();
                status.accept("Connecting…");
                return value;
            }
        });
    }

    private @Nullable String signIn(McpServerHandle server, McpSignInPrompt prompt) {
        var connection = server.connection;
        var url = connection != null ? connection.oauthUrl() : null;
        if (connection == null || url == null) {
            return "MCP server \"" + server.entry.name() + "\" does not use OAuth.";
        }
        try {
            McpSignIn.signIn(new McpSignIn.Options(url, lifecycle.credentials()
                .forServer(server.entry.name(), url), connection.oauthSettings(),
                connection.challenge(), prompt, "pi-java"));
        } catch (McpSignInCancelledError cancelled) {
            return "Sign-in cancelled.";
        } catch (Exception error) {
            return "Sign-in failed: " + McpExtension.errorMessage(error);
        }
        // 促成登录的 challenge（如升 scope）已应答
        connection.clearChallenge();
        try {
            connection.reconnect().join();
        } catch (RuntimeException error) {
            return "Signed in, but " + McpExtension.errorMessage(error);
        }
        return null;
    }

    private boolean signOut(McpServerHandle server) {
        var connection = server.connection;
        var url = connection != null ? connection.oauthUrl() : null;
        if (connection == null || url == null) {
            return false;
        }
        var removed = lifecycle.credentials().remove(server.entry.name(), url);
        connection.signOut().join();
        return removed;
    }

    private void openUrl(String url) {
        // URL 已由 showAuthorizationUrl 通知/上屏 ⇒ headless 提示静默（pi 静默失败臂对位）
        com.pijava.coding.agent.subcommand.BrowserLauncher.open(url, message -> { });
    }

    // ── 管理循环（pi runInBackground/runAction/manage :959-1010） ──

    /** 订阅中的菜单在连接开关时保持可用（后台跑，结果换代/abort/移除即丢）。 */
    private void runInBackground(McpServerHandle server,
                                 Supplier<CompletableFuture<String>> operation) {
        var ready = operation.get();
        var attempt = server.attempt;
        var currentScope = scope.get();
        Consumer<String> finish = message -> {
            if (currentScope.isAborted() || server.attempt != attempt
                || !state.servers.contains(server)) {
                return;
            }
            server.message = message;
            lifecycle.warnUnreachableOnce(ctx);
            state.emitChange();
        };
        currentScope.track(ready.thenApply(message -> {
            finish.accept(message);
            return null;
        }).exceptionally(error -> {
            finish.accept(McpExtension.errorMessage(error));
            return null;
        }));
    }

    private void runAction(McpManagerUi ui, McpServerHandle server, String action) {
        var currentScope = scope.get();
        String message = null;
        switch (action) {
            case "signin" -> message = signInWithUi(ui, server);
            case "reconnect" -> runInBackground(server, () ->
                CompletableFuture.supplyAsync(() -> {
                    ops.reconnect(server);
                    return null;
                }, executor));
            case "signout" -> signOut(server);
            case "tools" -> {
                // 纯展示菜单：直至返回键
                ui.menu(() -> McpManagerMenus.toolsMenu(server), null).join();
            }
            case "exposure" -> message = chooseExposure(ui, server);
            case "enable", "disable", "enable-project", "disable-project" -> {
                var enable = action.startsWith("enable");
                runInBackground(server, () -> CompletableFuture.supplyAsync(
                    () -> ops.setEnabled(server, enable, action.endsWith("-project")), executor));
            }
            default -> {
                // 不可达（菜单只产已知值）
            }
        }
        // 会话在此期间结束 ⇒ ctx 陈旧，不再写状态（pi signal.aborted 对位）
        if (currentScope.isAborted()) {
            return;
        }
        server.message = message;
        lifecycle.warnUnreachableOnce(ctx);
        state.emitChange();
    }

    /** pi {@code manage}（:1002-1010）：服务器菜单 ⇄ 动作菜单双循环。 */
    void manage(McpManagerUi ui) {
        for (;;) {
            var name = ui.menu(() -> McpManagerMenus.serversMenu(state),
                state::subscribe).join();
            if (name == null) {
                return;
            }
            for (;;) {
                var action = ui.menu(() -> McpManagerMenus.serverMenu(state, name),
                    state::subscribe).join();
                var server = state.findServer(name);
                if (action == null || server == null) {
                    break;
                }
                runAction(ui, server, action);
            }
        }
    }

    private @Nullable String chooseExposure(McpManagerUi ui, McpServerHandle server) {
        var current = McpExtensionState.exposureOf(server.entry);
        var choice = ui.menu(() -> McpManagerMenus.exposureMenu(server), null).join();
        if (choice == null || choice.equals(current.wire())) {
            return null;
        }
        return ops.setExposure(server, McpExposure.fromWire(choice));
    }

    // ── 视图挂载（pi showMcpManager，ui.ts:247-258） ──

    /**
     * 经 {@code ctx.ui.custom} 开管理器视图并跑 {@code manage} 至返回
     * （TUI 阻塞调用线程；pi 的 custom 回调/done 对位）。
     */
    void showMcpManager(Consumer<McpManagerUi> manage) {
        ctx.ui().custom((env, keybindings, done) -> {
            var view = new McpManagerView(env, keybindings, () -> done.accept(null));
            CompletableFuture.runAsync(() -> {
                try {
                    manage.accept(view);
                } catch (RuntimeException error) {
                    ctx.ui().notify(McpExtension.errorMessage(error), NotifyType.ERROR);
                } finally {
                    view.finish();
                }
            }, executor);
            return view;
        }, ExtensionCustomOptions.DEFAULT);
    }
}
