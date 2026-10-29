package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.pijava.ai.auth.AnthropicOAuthFlow;
import com.pijava.ai.auth.ChatGptOAuthFlow;
import com.pijava.ai.auth.DeviceCodeConfig;
import com.pijava.ai.auth.DeviceCodeFlow;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthConfig;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.auth.OAuthFlow;
import com.pijava.ai.auth.OAuthProvider;
import com.pijava.ai.auth.OAuthProviders;
import com.pijava.ai.auth.RadiusOAuthConfig;
import com.pijava.ai.auth.RadiusOAuthFlow;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.FileModelsStore;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.DefaultModelIds;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ProviderRegistry;
import org.jspecify.annotations.Nullable;

/** Saves provider credentials, then synchronizes catalog and default-model state. */
public final class ProviderLoginService implements LoginExecutor {

    /** Successful credential save; post-save synchronization warnings do not revoke it. */
    public record LoginSuccess(
        String providerId,
        String providerName,
        LoginAuthType authType,
        String status,
        @Nullable String warning,
        @Nullable ModelId<?> selectedModel
    ) {}

    /** Session operations needed by post-login synchronization. */
    interface SessionSink {
        ModelId<?> currentModel();
        ModelCatalog modelCatalog();
        void modelCatalog(ModelCatalog catalog);
        void setModel(ModelId<?> model, boolean persist);
    }

    /** Credential-stage operation; OAuth can be replaced by a local test rig. */
    @FunctionalInterface
    interface Authenticator {
        void login(LoginProviderOption option, LoginInteraction interaction) throws IOException;
    }

    private record CatalogSelection(@Nullable ModelId<?> model, @Nullable String warning) {}

    private final SessionSink session;
    private final ProviderRegistry providers;
    private final ModelCatalogRefresh refresh;
    private final Authenticator authenticator;
    private final Path apiKeyAuthPath;
    private final Path oauthAuthPath;
    private final Duration refreshTimeout;

    /** Create the production login service bound to one session. */
    public ProviderLoginService(AgentSession session) {
        this(new AgentSessionSink(session), session.services().providers(),
            new ModelCatalogRefresh(FileModelsStore.defaultStore()), authenticator(session),
            defaultAuthPath("auth.json"), defaultAuthPath("auth-oauth.json"),
            Duration.ofSeconds(15));
    }

    ProviderLoginService(SessionSink session, ProviderRegistry providers,
                         ModelCatalogRefresh refresh, Authenticator authenticator,
                         Path apiKeyAuthPath, Path oauthAuthPath, Duration refreshTimeout) {
        this.session = session;
        this.providers = providers;
        this.refresh = refresh;
        this.authenticator = authenticator;
        this.apiKeyAuthPath = apiKeyAuthPath;
        this.oauthAuthPath = oauthAuthPath;
        this.refreshTimeout = refreshTimeout;
    }

    @Override
    public LoginSuccess login(LoginProviderOption option, LoginInteraction interaction)
            throws IOException {
        var previousModel = session.currentModel();
        boolean wasUnknown = previousModel == null
            || session.modelCatalog().find(previousModel).isEmpty();
        authenticator.login(option, interaction);

        var action = actionLabel(option);
        var savedStatus = action + ". Credentials saved to " + authPath(option);
        var warning = refreshWarning(action, option.id());
        try {
            session.modelCatalog(rebuildCatalog());
        } catch (RuntimeException e) {
            warning = merge(warning, action
                + ", but local model state could not be synchronized: " + messageOf(e));
            return success(option, savedStatus, warning, null);
        }

        ModelId<?> selected = null;
        var status = savedStatus;
        if (wasUnknown && Objects.equals(previousModel, session.currentModel())) {
            var selection = selectDefault(option, action);
            if (selection.warning() != null) {
                warning = merge(warning, selection.warning());
            }
            if (selection.model() != null) {
                try {
                    session.setModel(selection.model(), true);
                    selected = selection.model();
                    status = action + ". Selected " + selected.modelName()
                        + ". Credentials saved to " + authPath(option);
                } catch (RuntimeException e) {
                    warning = merge(warning, action + ", but selecting its default model failed: "
                        + messageOf(e) + ". Use /model to select a model.");
                }
            }
        }
        return success(option, status, warning, selected);
    }

    static Authenticator standardAuthenticator(
            FileCredentialStore apiKeys, OAuthCredentialStore oauthCredentials,
            Supplier<ChatGptOAuthFlow.LoginHints> chatGptHints) {
        return (option, interaction) -> {
            if (option.authType() == LoginAuthType.API_KEY) {
                var key = interaction.secret("Enter " + option.name() + " API key");
                apiKeys.storeApiKey(option.id(), key);
                return;
            }
            var spec = OAuthProviders.get(option.id()).orElseThrow(() ->
                new IOException("No OAuth flow registered for provider '" + option.id() + "'"));
            var credential = switch (spec) {
                case OAuthProvider.Pkce(OAuthConfig c) -> new OAuthFlow(c).login(interaction);
                case OAuthProvider.Anthropic ignored -> new AnthropicOAuthFlow().login(interaction);
                case OAuthProvider.ChatGpt ignored -> new ChatGptOAuthFlow()
                    .login(interaction, chatGptHints.get());
                case OAuthProvider.Device(DeviceCodeConfig c) -> new DeviceCodeFlow(c).login(interaction);
                case OAuthProvider.Radius(RadiusOAuthConfig c) -> new RadiusOAuthFlow(c).login(interaction);
            };
            oauthCredentials.store(option.id(), credential);
        };
    }

    private static Authenticator authenticator(AgentSession session) {
        return standardAuthenticator(new FileCredentialStore(), new OAuthCredentialStore(), () ->
            new ChatGptOAuthFlow.LoginHints(
                () -> session.services().settings().install().getOrCreateDeviceId(), "Pi"));
    }

    private String refreshWarning(String action, String providerId) {
        var provider = providers.get(providerId);
        if (provider.isEmpty()) {
            return action + ", but its model catalog could not be refreshed: unknown provider";
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        var task = executor.submit(() -> refresh.refresh(List.of(provider.get()), true, false));
        try {
            var result = task.get(refreshTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return result.hasErrors()
                ? action + ", but its model catalog could not be refreshed; using cached models."
                : null;
        } catch (TimeoutException e) {
            task.cancel(true);
            return action + ", but its model catalog refresh timed out; using cached models.";
        } catch (ExecutionException e) {
            return action + ", but its model catalog could not be refreshed: "
                + messageOf(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return action + ", but its model catalog could not be refreshed: Login cancelled";
        } finally {
            executor.shutdownNow();
        }
    }

    private ModelCatalog rebuildCatalog() {
        return BuiltinCatalog.of(providers.listAll().stream()
            .flatMap(provider -> provider.getModels().stream()).toList());
    }

    private CatalogSelection selectDefault(LoginProviderOption option, String action) {
        var defaultId = DefaultModelIds.forProvider(option.id());
        if (defaultId.isEmpty()) {
            return new CatalogSelection(null, action + ", but no default model is configured for provider \""
                + option.id() + "\". Use /model to select a model.");
        }
        var providerModels = session.modelCatalog().listModels().stream()
            .filter(model -> model.id().provider().equals(option.id())).toList();
        if (providerModels.isEmpty()) {
            return new CatalogSelection(null, action
                + ", but no models are available for that provider. Use /model to select a model.");
        }
        ModelId<?> selected = providerModels.stream()
            .filter(model -> model.id().modelName().equals(defaultId.get()))
            .map(ModelInfo::id).findFirst().orElse(null);
        if (selected == null && "radius".equals(option.id())) {
            selected = providerModels.getFirst().id();
        }
        if (selected == null) {
            return new CatalogSelection(null, action + ", but its default model \""
                + defaultId.get() + "\" is not available. Use /model to select a model.");
        }
        return new CatalogSelection(selected, null);
    }

    private static LoginSuccess success(LoginProviderOption option, String status,
                                        @Nullable String warning, @Nullable ModelId<?> selected) {
        return new LoginSuccess(option.id(), option.name(), option.authType(),
            status, warning, selected);
    }

    private static String actionLabel(LoginProviderOption option) {
        return (option.authType() == LoginAuthType.OAUTH ? "Logged in to " : "Saved API key for ")
            + option.name();
    }

    private Path authPath(LoginProviderOption option) {
        return option.authType() == LoginAuthType.OAUTH ? oauthAuthPath : apiKeyAuthPath;
    }

    private static Path defaultAuthPath(String filename) {
        return Path.of(System.getProperty("user.home"), ".pi-java", filename);
    }

    private static String merge(@Nullable String first, @Nullable String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return second == null || second.isBlank() ? first : first + " " + second;
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static final class AgentSessionSink implements SessionSink {
        private final AgentSession session;

        private AgentSessionSink(AgentSession session) {
            this.session = session;
        }

        @Override public ModelId<?> currentModel() { return session.harness().getModel(); }
        @Override public ModelCatalog modelCatalog() { return session.modelTools().modelCatalog(); }

        @Override
        public void modelCatalog(ModelCatalog catalog) {
            session.modelTools().modelCatalog(catalog);
        }

        @Override
        public void setModel(ModelId<?> model, boolean persist) {
            session.modelTools().setModel(model, persist);
        }
    }
}
