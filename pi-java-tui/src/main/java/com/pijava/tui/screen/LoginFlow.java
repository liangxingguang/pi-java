package com.pijava.tui.screen;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.LoginExecutor;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.coding.agent.core.ProviderLoginCatalog;
import com.pijava.coding.agent.core.ProviderLoginService;
import com.pijava.coding.agent.core.RadiusMcpOffer;
import org.jspecify.annotations.Nullable;

/** Provider-login state machine, independent of terminal rendering. */
public final class LoginFlow {

    /** Currently visible step. */
    public enum Screen { AUTH_TYPE, PROVIDER, DIALOG, MCP_OFFER, CLOSED }

    /** Host effects kept outside the domain flow. */
    public interface Host {
        void open(ScreenOverlay overlay);
        void dismiss(ScreenOverlay overlay);
        void status(String message);
        void warning(String message);
        void error(String message);
        void reload();
    }

    private final List<LoginProviderOption> options;
    private final LoginExecutor executor;
    private final Path agentDir;
    private final Path cwd;
    private final Host host;
    private final Consumer<Runnable> dispatcher;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Screen currentScreen = Screen.CLOSED;
    private List<String> authTypeOptions = List.of();
    private LoginDialogScreen dialog;
    private Future<?> loginTask;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean providerOrigin;
    private volatile LoginAuthType originAuthType;
    private volatile String originSearch;

    /** Create a flow bound to one session's login executor and MCP paths. */
    public LoginFlow(List<LoginProviderOption> options, LoginExecutor executor,
                     Path agentDir, Path cwd, Host host, Consumer<Runnable> dispatcher) {
        this.options = List.copyOf(options);
        this.executor = executor;
        this.agentDir = agentDir;
        this.cwd = cwd;
        this.host = host;
        this.dispatcher = dispatcher;
    }

    /** Start from an optional /login provider argument. */
    public void begin(@Nullable String providerReference) {
        if (providerReference == null || providerReference.isBlank()) {
            showAuthTypeSelector(null);
            return;
        }
        var matches = ProviderLoginCatalog.find(options, providerReference);
        if (matches.size() == 1) {
            providerOrigin = false;
            startProviderLogin(matches.getFirst());
            return;
        }
        if (matches.size() > 1 && matches.stream().map(LoginProviderOption::id).distinct().count() == 1) {
            showAuthTypeSelector(matches);
            return;
        }
        showProviderSelector(null, providerReference);
    }

    /** Current flow step for hosts and interaction tests. */
    public Screen currentScreen() {
        return currentScreen;
    }

    void openProviderOptions() {
        showProviderSelector(null, null);
    }

    List<String> authTypeOptions() {
        return authTypeOptions;
    }

    private void showAuthTypeSelector(@Nullable List<LoginProviderOption> filtered) {
        providerOrigin = false;
        var source = filtered == null ? options : filtered;
        var labels = new java.util.ArrayList<String>();
        if (filtered == null || source.stream().anyMatch(o -> o.authType() == LoginAuthType.OAUTH)) {
            labels.add("Sign in with an account");
        }
        if (filtered == null || source.stream().anyMatch(o -> o.authType() == LoginAuthType.API_KEY)) {
            labels.add("Sign in with an API key");
        }
        if (filtered == null) {
            source.stream().filter(o -> o.id().equals("radius")
                    && o.authType() == LoginAuthType.OAUTH).findFirst()
                .ifPresent(o -> labels.add("Sign in with " + o.name()
                    + (o.status() == null ? "" : " — " + o.status())));
        }
        if (labels.isEmpty()) {
            host.status("No login methods available.");
            closeFlow();
            return;
        }
        if (filtered != null && labels.size() == 1) {
            var authType = labels.getFirst().contains("API key")
                ? LoginAuthType.API_KEY : LoginAuthType.OAUTH;
            startProviderLogin(filtered.stream().filter(o -> o.authType() == authType)
                .findFirst().orElseThrow());
            return;
        }
        authTypeOptions = List.copyOf(labels);
        currentScreen = Screen.AUTH_TYPE;
        host.open(new BugReportChoiceScreen("Sign in", authTypeOptions, "", choice -> {
            if (choice == null) {
                closeFlow();
            } else if (choice.equals("Sign in with an account")) {
                showProviderSelector(LoginAuthType.OAUTH, null);
            } else if (choice.equals("Sign in with an API key")) {
                showProviderSelector(LoginAuthType.API_KEY, null);
            } else {
                source.stream().filter(o -> o.id().equals("radius")
                        && o.authType() == LoginAuthType.OAUTH).findFirst()
                    .ifPresent(this::startProviderLogin);
            }
        }));
    }

    private void showProviderSelector(@Nullable LoginAuthType authType, @Nullable String search) {
        var filtered = options.stream().filter(o -> authType == null || o.authType() == authType).toList();
        if (filtered.isEmpty()) {
            host.status(authType == LoginAuthType.OAUTH ? "No account providers available."
                : authType == LoginAuthType.API_KEY ? "No API key providers available."
                : "No login providers available.");
            closeFlow();
            return;
        }
        providerOrigin = true;
        originAuthType = authType;
        originSearch = search;
        currentScreen = Screen.PROVIDER;
        host.open(new LoginProviderScreen(filtered, search, choice -> {
            if (choice == null) {
                closeFlow();
            } else {
                startProviderLogin(choice);
            }
        }));
    }

    void startProviderLogin(LoginProviderOption option) {
        long ticket = generation.incrementAndGet();
        currentScreen = Screen.DIALOG;
        dialog = new LoginDialogScreen(option, dispatcher, this::cancelDialog);
        host.open(dialog);
        loginTask = workers.submit(() -> {
            try {
                var result = executor.login(option, dialog);
                dispatcher.accept(() -> {
                    if (ticket == generation.get()) {
                        completeLogin(option, result);
                    }
                });
            } catch (Exception e) {
                dispatcher.accept(() -> {
                    if (ticket == generation.get()) {
                        failLogin(option, e);
                    }
                });
            }
        });
    }

    void cancelDialog() {
        if (currentScreen != Screen.DIALOG) {
            return;
        }
        generation.incrementAndGet();
        if (loginTask != null) {
            loginTask.cancel(true);
        }
        currentScreen = Screen.CLOSED;
        var oldDialog = dialog;
        dialog = null;
        if (oldDialog != null) {
            host.dismiss(oldDialog);
        }
        returnToOrigin();
    }

    private void completeLogin(LoginProviderOption option, ProviderLoginService.LoginSuccess result) {
        dismissDialog();
        host.status(result.status());
        if (result.warning() != null && !result.warning().isBlank()) {
            host.warning(result.warning());
        }
        if (!option.id().equals("radius") || option.authType() != LoginAuthType.OAUTH) {
            closeFlow();
            return;
        }
        RadiusMcpOffer.Decision decision;
        try {
            decision = RadiusMcpOffer.prepare(agentDir, cwd);
        } catch (RuntimeException e) {
            host.error("Could not update " + agentDir.resolve("mcp.json") + ": " + e.getMessage());
            closeFlow();
            return;
        }
        if (decision.alreadyConfigured()) {
            closeFlow();
            return;
        }
        currentScreen = Screen.MCP_OFFER;
        host.open(new BugReportChoiceScreen(
            "Configure " + option.name() + " MCP in " + agentDir.resolve("mcp.json") + "?",
            List.of("Yes", "No"), "", choice -> {
                if ("Yes".equals(choice)) {
                    try {
                        if (RadiusMcpOffer.apply(agentDir, decision)) {
                            host.reload();
                        }
                    } catch (RuntimeException e) {
                        host.error("Could not update " + agentDir.resolve("mcp.json") + ": " + e.getMessage());
                    }
                }
                closeFlow();
            }));
    }

    private void failLogin(LoginProviderOption option, Exception error) {
        dismissDialog();
        if (isCancellation(error)) {
            returnToOrigin();
            return;
        }
        var prefix = option.authType() == LoginAuthType.OAUTH
            ? "Failed to login to " : "Failed to save API key for ";
        host.error(prefix + option.name() + ": "
            + (error.getMessage() == null ? error.toString() : error.getMessage()));
        closeFlow();
    }

    private void dismissDialog() {
        var oldDialog = dialog;
        dialog = null;
        if (oldDialog != null) {
            host.dismiss(oldDialog);
        }
    }

    private void returnToOrigin() {
        if (providerOrigin) {
            showProviderSelector(originAuthType, originSearch);
        } else {
            closeFlow();
        }
    }

    private void closeFlow() {
        generation.incrementAndGet();
        currentScreen = Screen.CLOSED;
        workers.shutdownNow();
    }

    private static boolean isCancellation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof CancellationException || "Login cancelled".equals(current.getMessage())) {
                return true;
            }
        }
        return false;
    }
}
