package com.pijava.coding.agent.subcommand;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.pijava.ai.auth.ChatGptOAuthFlow;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.auth.OAuthEvent;
import com.pijava.ai.auth.OAuthProviders;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.WebUrl;
import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.LoginInteraction;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.coding.agent.core.ModelCatalogRefresh;
import com.pijava.coding.agent.core.ProviderLoginCatalog;
import com.pijava.coding.agent.core.ProviderLoginService;
import com.pijava.coding.agent.core.ProviderMatching;
import com.pijava.coding.agent.core.RadiusMcpPlan;
import com.pijava.mcp.config.McpConfig;
import org.jspecify.annotations.Nullable;

/**
 * {@code pi-java auth login <provider>} (pi {@code cli/auth-login.ts}, anchor
 * {@code a00a5048d}).
 *
 * <p>Logs in with an account or stores an API key, refreshes that provider's model
 * catalog, and points the Radius MCP server at the Radius login.</p>
 */
public final class AuthLoginCommand {

    private static final String APP_NAME = "pi-java";
    static final String USAGE = APP_NAME + " auth login <provider> [--api-key] [--no-browser]";
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(15);

    /** Dependencies of {@code auth login}, injected so tests can script the terminal. */
    public record Options(AuthTerminal terminal, ProviderRegistry providers, Path agentDir, Path cwd,
                          boolean offline, FileCredentialStore apiKeys,
                          OAuthCredentialStore oauthCredentials,
                          Supplier<ChatGptOAuthFlow.LoginHints> chatGptHints,
                          ModelCatalogRefresh refresh) {}

    private AuthLoginCommand() {}

    /** Production dependencies: default stores, providers, and a 15s catalog refresh. */
    public static Options production() {
        return new Options(new ProcessAuthTerminal(),
            com.pijava.coding.agent.core.DefaultProviders.defaultProviders(),
            Path.of(System.getProperty("user.home"), ".pi-java"),
            Path.of(System.getProperty("user.dir")),
            false, new FileCredentialStore(), new OAuthCredentialStore(),
            () -> new ChatGptOAuthFlow.LoginHints(() -> com.pijava.coding.agent.core.SettingsManager
                .load(null, null, Boolean.TRUE).install().getOrCreateDeviceId(), "Pi"),
            new ModelCatalogRefresh(com.pijava.ai.catalog.FileModelsStore.defaultStore()));
    }

    /** Run {@code auth login <provider>} and return the process exit code. */
    public static int run(String[] args, Options options) {
        var parsed = parseRequest(args);
        if (parsed instanceof Parsed.Invalid invalid) {
            options.terminal().error(invalid.message());
            return 1;
        }
        var request = ((Parsed.Valid) parsed).request();
        var provider = ProviderMatching.findMatching(
            options.providers().listAll(), request.providerRef()).stream().findFirst().orElse(null);
        if (provider == null) {
            options.terminal().error("Unknown provider \""
                + WebUrl.replaceControlCharacters(request.providerRef()) + "\".");
            options.terminal().error("Providers: " + String.join(", ", loginProviderIds(
                options.providers().listAll())));
            return 1;
        }
        if (!options.terminal().interactive()) {
            options.terminal().error("\"" + USAGE + "\" requires an interactive terminal");
            return 1;
        }

        var authType = selectAuthType(provider, request.apiKey());
        if (authType instanceof AuthChoice.Unavailable unavailable) {
            options.terminal().error(unavailable.message());
            return 1;
        }
        var type = ((AuthChoice.Type) authType).type();
        var option = new LoginProviderOption(provider.name(), provider.displayName(), type, null, false);
        var interaction = new CliLoginInteraction(type, request, options.terminal());

        try {
            ProviderLoginService.standardAuthenticator(options.apiKeys(),
                options.oauthCredentials(), options.chatGptHints()).login(option, interaction);
        } catch (IOException | RuntimeException error) {
            var message = messageOf(error);
            if (message.equals("Login cancelled")) {
                options.terminal().error("Login cancelled.");
            } else {
                options.terminal().error("Failed to log in to " + provider.displayName()
                    + ": " + message);
            }
            return 1;
        }

        var success = type == LoginAuthType.OAUTH
            ? "Logged in to " + provider.displayName()
            : "Saved API key for " + provider.displayName();
        options.terminal().write(success + ". Credentials saved to " + authPath(options, type));
        refreshProviderCatalog(provider, options);
        if (provider.name().equals("radius")) {
            configureRadiusMcpServer(provider, options);
        }
        return 0;
    }

    // ── Parsing and auth selection ───────────────────────────

    /** Parsed {@code auth login} arguments (pi {@code LoginRequest}). */
    record LoginRequest(String providerRef, boolean apiKey, boolean browser) {}

    sealed interface Parsed permits Parsed.Valid, Parsed.Invalid {
        record Valid(LoginRequest request) implements Parsed {}
        record Invalid(String message) implements Parsed {}
    }

    static Parsed parseRequest(String[] args) {
        var apiKey = false;
        var browser = true;
        var ref = "";
        for (var arg : args) {
            if ("--offline".equals(arg)) {
                // Global flag: main strips it before the login command (pi main.ts:130-170).
                continue;
            } else if ("--api-key".equals(arg)) {
                apiKey = true;
            } else if ("--no-browser".equals(arg)) {
                browser = false;
            } else if (arg.startsWith("-") || !ref.isEmpty()) {
                return new Parsed.Invalid("Usage: " + USAGE);
            } else {
                ref = arg;
            }
        }
        if (ref.isEmpty()) {
            return new Parsed.Invalid("Usage: " + USAGE);
        }
        if (apiKey && !browser) {
            return new Parsed.Invalid(
                "--no-browser only applies to account sign-in; omit it with --api-key.");
        }
        return new Parsed.Valid(new LoginRequest(ref, apiKey, browser));
    }

    sealed interface AuthChoice permits AuthChoice.Type, AuthChoice.Unavailable {
        record Type(LoginAuthType type) implements AuthChoice {}
        record Unavailable(String message) implements AuthChoice {}
    }

    /**
     * Account login unless {@code apiKey} asks for an API key. Providers without
     * account login use their API key login, since that is the only way in.
     */
    static AuthChoice selectAuthType(Provider provider, boolean apiKey) {
        var hasOauth = OAuthProviders.get(provider.name()).isPresent();
        var apiKeyName = apiKeyLoginName(provider);
        var hasApiKeyLogin = apiKeyName != null;
        if (!apiKey && hasOauth) {
            return new AuthChoice.Type(LoginAuthType.OAUTH);
        }
        if (hasApiKeyLogin) {
            return new AuthChoice.Type(LoginAuthType.API_KEY);
        }
        if (apiKey && hasOauth) {
            return new AuthChoice.Unavailable(provider.displayName()
                + " has no API key login. Omit --api-key to sign in.");
        }
        var method = apiKeyName == null ? "ambient credentials" : apiKeyName;
        return new AuthChoice.Unavailable(provider.displayName()
            + " is configured outside " + APP_NAME + " (" + method + ").");
    }

    /** Splits the subscription choice per provider (pi {@code auth.apiKey?.name}). */
    private static @Nullable String apiKeyLoginName(Provider provider) {
        var configurable = ProviderLoginCatalog.configurable(provider);
        return configurable == null ? null : configurable.providerConfig().apiKeyEnvVar();
    }

    static List<String> loginProviderIds(List<Provider> providers) {
        return providers.stream()
            .filter(provider -> OAuthProviders.get(provider.name()).isPresent()
                || apiKeyLoginName(provider) != null)
            .map(Provider::name)
            .sorted()
            .toList();
    }

    // ── Login interaction ────────────────────────────────────

    /** Provider prompts and events adapted to the command's selected sign-in method. */
    static final class CliLoginInteraction implements LoginInteraction {
        private final LoginAuthType authType;
        private final LoginRequest request;
        private final AuthTerminal terminal;
        private List<String> skippedMethods = List.of();

        CliLoginInteraction(LoginAuthType authType, LoginRequest request, AuthTerminal terminal) {
            this.authType = authType;
            this.request = request;
            this.terminal = terminal;
        }

        @Override
        public boolean handlesStructuredEvents() {
            return true;
        }

        @Override
        public void notify(String message) {
            terminal.write(message);
        }

        @Override
        public void notify(OAuthEvent event) {
            switch (event) {
                case OAuthEvent.AuthUrl authUrl -> onAuthUrl(authUrl);
                case OAuthEvent.DeviceCode device -> onDeviceCode(device);
                case OAuthEvent.Info info -> onInfo(info);
                case OAuthEvent.Progress progress -> terminal.write(progress.message());
            }
        }

        private void onAuthUrl(OAuthEvent.AuthUrl event) {
            terminal.write("Open this URL to sign in:\n  " + WebUrl.displayUrl(event.url()));
            if (event.instructions() != null && !event.instructions().isBlank()) {
                terminal.write(event.instructions());
            }
            openWebUrl(event.url());
            // Providers without a method choice have nothing else to offer, so no hint.
            var rerun = "\"" + APP_NAME + " auth login " + request.providerRef() + " --no-browser\"";
            var skipped = skippedMethods;
            if (skipped.size() == 1) {
                terminal.write("To use \"" + skipped.getFirst() + "\" instead, run " + rerun);
            } else if (skipped.size() > 1) {
                terminal.write("Other sign-in methods: "
                    + skipped.stream().map(label -> "\"" + label + "\"")
                        .reduce((a, b) -> a + ", " + b).orElse("")
                    + ". Run " + rerun + " to choose one.");
            }
        }

        private void onDeviceCode(OAuthEvent.DeviceCode event) {
            terminal.write("Open " + WebUrl.displayUrl(event.verificationUri())
                + " and enter code: " + event.userCode());
            if (event.openBrowserOnConfirm()) {
                openWebUrl(event.verificationUri());
            }
            terminal.write("Waiting for authentication...");
        }

        private void onInfo(OAuthEvent.Info event) {
            terminal.write(event.message());
            for (var link : event.links()) {
                terminal.write("  " + (link.label() == null ? "" : link.label() + ": ")
                    + WebUrl.displayUrl(link.url()));
            }
        }

        private void openWebUrl(String value) {
            if (request.browser()) {
                WebUrl.webUrl(value).ifPresent(terminal::openUrl);
            }
        }

        @Override
        public String prompt(String message) {
            try {
                return terminal.question(message);
            } catch (IOException e) {
                throw cancelOrFail(e);
            }
        }

        @Override
        public String secret(String message) {
            try {
                return terminal.secret(message);
            } catch (IOException e) {
                throw cancelOrFail(e);
            }
        }

        private static RuntimeException cancelOrFail(IOException error) {
            return new RuntimeException(error.getMessage(), error);
        }

        @Override
        public String select(String message, List<LoginOption> options) throws IOException {
            if (options.isEmpty()) {
                return "";
            }
            var first = options.getFirst();
            // Account logins list the browser sign-in first; API key setup asks every selection.
            var methodChoice = authType == LoginAuthType.API_KEY ? "ask"
                : request.browser() ? "browser" : "other";
            if (methodChoice.equals("ask")) {
                return selectInTerminal(message, options);
            }
            if (methodChoice.equals("browser")) {
                skippedMethods = options.subList(1, options.size()).stream()
                    .map(LoginOption::label).toList();
                return first.id();
            }
            var otherOptions = options.subList(1, options.size());
            // A provider whose only method is the browser sign-in still gets it,
            // without opening the browser.
            if (otherOptions.isEmpty()) {
                return first.id();
            }
            if (otherOptions.size() > 1) {
                return selectInTerminal(message, otherOptions);
            }
            terminal.write(message + " " + otherOptions.getFirst().label());
            return otherOptions.getFirst().id();
        }

        private String selectInTerminal(String message, List<LoginOption> options) throws IOException {
            terminal.write(message);
            for (var index = 0; index < options.size(); index++) {
                terminal.write("  " + (index + 1) + ". " + options.get(index).label());
            }
            while (true) {
                var answer = terminal.question("Select 1-" + options.size() + ": ").trim();
                if (answer.matches("^\\d+$")) {
                    var index = Integer.parseInt(answer) - 1;
                    if (index >= 0 && index < options.size()) {
                        return options.get(index).id();
                    }
                }
                terminal.write("Enter a number from 1 to " + options.size() + ".");
            }
        }
    }

    // ── Post-login steps ─────────────────────────────────────

    private static void refreshProviderCatalog(Provider provider, Options options) {
        if (options.offline()) {
            return;
        }
        var refresh = options.refresh();
        if (refresh == null) {
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> refresh.refresh(List.of(provider), true, false));
            String note = null;
            try {
                var result = task.get(REFRESH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (result.hasErrors()) {
                    note = catalogNote(provider, "could not be refreshed; " + APP_NAME
                        + " retries on next start.");
                }
            } catch (TimeoutException e) {
                task.cancel(true);
                note = catalogNote(provider, "refresh timed out; " + APP_NAME
                    + " refreshes it on next start.");
            } catch (ExecutionException e) {
                note = catalogNote(provider, "could not be refreshed: "
                    + messageOf(e.getCause() == null ? e : e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task.cancel(true);
                note = catalogNote(provider, "refresh was interrupted; " + APP_NAME
                    + " refreshes it on next start.");
            }
            if (note != null) {
                options.terminal().write(note);
            }
        }
    }

    private static String catalogNote(Provider provider, String tail) {
        return "The " + provider.displayName() + " model catalog " + tail;
    }

    private static void configureRadiusMcpServer(Provider provider, Options options) {
        RadiusMcpPlan.plan(provider.name(), options.agentDir(), options.cwd()).ifPresent(plan -> {
            try {
                var replaced = McpConfig.add(plan.mcpPath(), plan.name(), plan.config());
                options.terminal().write((replaced ? "Updated" : "Added") + " MCP server \""
                    + plan.name() + "\" in " + plan.mcpPath() + " to use the "
                    + provider.displayName() + " login.");
            } catch (RuntimeException e) {
                options.terminal().error("Could not update " + plan.mcpPath() + ": "
                    + messageOf(e));
            }
        });
    }

    private static String authPath(Options options, LoginAuthType type) {
        return type == LoginAuthType.OAUTH
            ? options.oauthCredentials().path().toString()
            : options.apiKeys().path().toString();
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
