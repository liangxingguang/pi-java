package com.pijava.coding.agent.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthCredential;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.auth.OAuthInteraction;
import com.pijava.ai.catalog.InMemoryModelsStore;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;
import com.pijava.ai.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderLoginServiceTest {

    @TempDir
    static Path tempRoot;

    @Test
    void savedCredentialIsNotReportedAsLoginFailureWhenRefreshFails() throws Exception {
        var rig = ProviderLoginServiceTestRig.create();
        rig.refreshFails(new IOException("offline"));

        var result = rig.service().login(ProviderLoginFixtures.apiKey("radius", "Radius"),
            rig.interactionWithSecret("key"));

        assertThat(rig.storedApiKey("radius")).isEqualTo("key");
        assertThat(rig.secretPrompt()).isEqualTo("Enter Radius API key");
        assertThat(result.status()).isEqualTo("Saved API key for Radius. Credentials saved to "
            + rig.authPath());
        assertThat(result.warning()).contains("model catalog could not be refreshed");
    }

    @Test
    void lateDefaultSelectionDoesNotReplaceUserModel() {
        var rig = ProviderLoginServiceTestRig.createWithUnknownCurrentModel();
        var refresh = rig.pauseRefresh();
        var future = rig.loginAsync(ProviderLoginFixtures.oauth("radius", "Radius"));

        rig.sessionSetModel(ModelId.of("google", "user-choice"));
        refresh.completeWithBalanced();

        assertThat(future.join().selectedModel()).isNull();
        assertThat(rig.currentModel()).isEqualTo(ModelId.of("google", "user-choice"));
    }

    @Test
    void unknownModelSelectsRadiusBalancedAndPersistsIt() throws Exception {
        var rig = ProviderLoginServiceTestRig.createWithUnknownCurrentModel();

        var result = rig.service().login(ProviderLoginFixtures.oauth("radius", "Radius"),
            rig.interactionWithSecret(""));

        assertThat(result.selectedModel()).isEqualTo(ModelId.of("radius", "balanced"));
        assertThat(rig.currentModel()).isEqualTo(ModelId.of("radius", "balanced"));
        assertThat(rig.persistedModel()).isEqualTo(ModelId.of("radius", "balanced"));
        assertThat(result.status()).isEqualTo("Logged in to Radius. Selected balanced. "
            + "Credentials saved to " + rig.oauthAuthPath());
    }

    @Test
    void radiusFallsBackToFirstCatalogModelWhenBalancedIsAbsent() throws Exception {
        var rig = ProviderLoginServiceTestRig.createWithUnknownCurrentModel();
        rig.provider().afterRefresh = List.of(ProviderLoginFixtures.radiusModel("fast"));

        var result = rig.service().login(ProviderLoginFixtures.oauth("radius", "Radius"),
            rig.interactionWithSecret(""));

        assertThat(result.selectedModel()).isEqualTo(ModelId.of("radius", "fast"));
        assertThat(rig.currentModel()).isEqualTo(ModelId.of("radius", "fast"));
    }

    /** Real stores/refresh with a controllable provider and an in-memory session port. */
    private static final class ProviderLoginServiceTestRig {
        private final Path directory;
        private final Path authPath;
        private final Path oauthAuthPath;
        private final FileCredentialStore apiKeys;
        private final OAuthCredentialStore oauthCredentials;
        private final ControlledProvider provider = new ControlledProvider();
        private final MemorySession session = new MemorySession();
        private final ProviderLoginService service;
        private volatile String secretPrompt;

        private ProviderLoginServiceTestRig(Path directory) {
            this.directory = directory;
            this.authPath = directory.resolve("auth.json");
            this.oauthAuthPath = directory.resolve("auth-oauth.json");
            this.apiKeys = new FileCredentialStore(authPath);
            this.oauthCredentials = new OAuthCredentialStore(oauthAuthPath);
            var standard = ProviderLoginService.standardAuthenticator(
                apiKeys, oauthCredentials, () -> null);
            ProviderLoginService.Authenticator authenticator = (option, interaction) -> {
                if (option.authType() == LoginAuthType.OAUTH) {
                    oauthCredentials.store(option.id(),
                        new OAuthCredential("oauth-token", "refresh", Long.MAX_VALUE, null));
                } else {
                    standard.login(option, interaction);
                }
            };
            var registry = ProviderRegistry.create();
            registry.register(provider);
            service = new ProviderLoginService(session, registry,
                new ModelCatalogRefresh(new InMemoryModelsStore()), authenticator,
                authPath, oauthAuthPath, Duration.ofSeconds(5));
        }

        static ProviderLoginServiceTestRig create() {
            try {
                return new ProviderLoginServiceTestRig(Files.createTempDirectory(tempRoot, "login"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static ProviderLoginServiceTestRig createWithUnknownCurrentModel() {
            return create();
        }

        ProviderLoginService service() {
            return service;
        }

        ControlledProvider provider() {
            return provider;
        }

        Path authPath() {
            return authPath;
        }

        Path oauthAuthPath() {
            return oauthAuthPath;
        }

        String storedApiKey(String providerId) {
            return apiKeys.resolveApiKey(providerId).orElseThrow();
        }

        String secretPrompt() {
            return secretPrompt;
        }

        void refreshFails(IOException failure) {
            provider.failure = failure;
        }

        ControlledProvider pauseRefresh() {
            provider.paused = true;
            return provider;
        }

        CompletableFuture<ProviderLoginService.LoginSuccess> loginAsync(LoginProviderOption option) {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return service.login(option, interactionWithSecret(""));
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, Thread::startVirtualThread);
            provider.awaitOnlineRefresh();
            return future;
        }

        LoginInteraction interactionWithSecret(String secret) {
            return new LoginInteraction() {
                @Override public void notify(String message) {}

                @Override public String prompt(String message) {
                    return "";
                }

                @Override
                public String select(String message, List<OAuthInteraction.LoginOption> options) {
                    return options.getFirst().id();
                }

                @Override
                public String secret(String message) {
                    secretPrompt = message;
                    return secret;
                }
            };
        }

        void sessionSetModel(ModelId<?> model) {
            session.current = model;
        }

        ModelId<?> currentModel() {
            return session.current;
        }

        ModelId<?> persistedModel() {
            return session.persisted;
        }

        @Override
        public String toString() {
            return directory.toString();
        }
    }

    private static final class MemorySession implements ProviderLoginService.SessionSink {
        private volatile ModelId<?> current = ModelId.of("google", "unknown");
        private volatile ModelCatalog catalog = ModelCatalog.empty();
        private volatile ModelId<?> persisted;

        @Override public ModelId<?> currentModel() { return current; }
        @Override public ModelCatalog modelCatalog() { return catalog; }
        @Override public void modelCatalog(ModelCatalog replacement) { catalog = replacement; }

        @Override
        public void setModel(ModelId<?> model, boolean persist) {
            current = model;
            if (persist) {
                persisted = model;
            }
        }
    }

    private static final class ControlledProvider extends ConfigurableProvider {
        private final ProviderConfig config = ProviderConfig.single(
            "radius", "Radius", "https://radius.invalid", "RADIUS_API_KEY",
            Protocol.PI_MESSAGES, ModelCatalog.empty());
        private final CountDownLatch onlineEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile List<com.pijava.ai.catalog.ModelInfo> models = List.of();
        private volatile List<com.pijava.ai.catalog.ModelInfo> afterRefresh =
            List.of(ProviderLoginFixtures.radiusModel("balanced"));
        private volatile IOException failure;
        private volatile boolean paused;

        @Override protected ProviderConfig config() { return config; }

        @Override
        public List<com.pijava.ai.catalog.ModelInfo> getModels() {
            return models;
        }

        @Override
        public void refreshModels(RefreshModelsContext context) {
            if (!context.allowNetwork()) {
                return;
            }
            onlineEntered.countDown();
            if (failure != null) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
            if (paused) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release catalog refresh");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            models = afterRefresh;
        }

        void completeWithBalanced() {
            afterRefresh = List.of(ProviderLoginFixtures.radiusModel("balanced"));
            release.countDown();
        }

        void awaitOnlineRefresh() {
            try {
                if (!onlineEntered.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("login did not reach catalog refresh");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
