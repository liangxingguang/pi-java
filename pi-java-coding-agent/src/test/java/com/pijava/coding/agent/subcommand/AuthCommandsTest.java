package com.pijava.coding.agent.subcommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthCredential;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCommandsTest {

    @TempDir
    Path tmp;

    private ProviderRegistry registry() {
        var registry = ProviderRegistry.create();
        registry.loadBuiltinProviders();
        return registry;
    }

    private AuthLoginCommand.Options loginOptions(ScriptedTerminal terminal) {
        return new AuthLoginCommand.Options(terminal, registry(), tmp, tmp, true,
            new FileCredentialStore(tmp.resolve("auth.json")),
            new OAuthCredentialStore(tmp.resolve("auth-oauth.json")),
            () -> {
                throw new AssertionError("ChatGPT hints must not be requested");
            }, null);
    }

    private AuthLogoutCommand.Options logoutOptions(ScriptedTerminal terminal) {
        return new AuthLogoutCommand.Options(terminal, registry(),
            new FileCredentialStore(tmp.resolve("auth.json")),
            new OAuthCredentialStore(tmp.resolve("auth-oauth.json")));
    }

    // ── auth login ──────────────────────────────────────────

    @Test
    void loginUnknownProviderListsLoginCapableProviders() {
        var terminal = new ScriptedTerminal(false);
        var code = AuthLoginCommand.run(new String[] {"does-not-exist"}, loginOptions(terminal));

        assertThat(code).isEqualTo(1);
        assertThat(terminal.errors()).containsExactly(
            "Unknown provider \"does-not-exist\".",
            "Providers: ant-ling, anthropic, deepseek, google, minimax, minimax-cn, mistral, "
                + "moonshotai, moonshotai-cn, openai, openai-images, openrouter, "
                + "openrouter-images, qwen-token-plan, qwen-token-plan-cn, "
                + "qwen-token-plan-individual, qwen-token-plan-individual-cn, radius, xai, "
                + "xiaomi, xiaomi-token-plan-ams, xiaomi-token-plan-cn, "
                + "xiaomi-token-plan-sgp, zai, zai-coding-cn");
    }

    @Test
    void loginRequiresProviderAndRejectsApiKeyWithNoBrowser() {
        var terminal = new ScriptedTerminal(false);
        assertThat(AuthLoginCommand.run(new String[] {}, loginOptions(terminal))).isEqualTo(1);
        assertThat(terminal.errors()).containsExactly("Usage: " + AuthLoginCommand.USAGE);

        terminal = new ScriptedTerminal(false);
        assertThat(AuthLoginCommand.run(
            new String[] {"anthropic", "--api-key", "--no-browser"}, loginOptions(terminal)))
            .isEqualTo(1);
        assertThat(terminal.errors()).containsExactly(
            "--no-browser only applies to account sign-in; omit it with --api-key.");
    }

    @Test
    void loginWithoutInteractiveTerminalIsAnExplicitError() {
        var terminal = new ScriptedTerminal(false);
        var code = AuthLoginCommand.run(new String[] {"openai"}, loginOptions(terminal));

        assertThat(code).isEqualTo(1);
        assertThat(terminal.errors()).containsExactly(
            "\"" + AuthLoginCommand.USAGE + "\" requires an interactive terminal");
    }

    @Test
    void apiKeyLoginStoresTheKeyAndReportsTheAuthPath() throws IOException {
        var terminal = new ScriptedTerminal(true).answerSecret("sk-test");
        var code = AuthLoginCommand.run(
            new String[] {"deepseek", "--api-key"}, loginOptions(terminal));

        assertThat(code).isZero();
        assertThat(new FileCredentialStore(tmp.resolve("auth.json")).resolveApiKey("deepseek"))
            .contains("sk-test");
        assertThat(terminal.output()).containsExactly(
            "Saved API key for DeepSeek. Credentials saved to "
                + tmp.resolve("auth.json"));
    }

    @Test
    void apiKeyLoginOnAnOAuthOnlyProviderIsUnavailable() {
        var provider = new StubProvider("openrouter", "OpenRouter");

        var choice = AuthLoginCommand.selectAuthType(provider, true);

        assertThat(choice).isInstanceOf(AuthLoginCommand.AuthChoice.Unavailable.class);
        assertThat(((AuthLoginCommand.AuthChoice.Unavailable) choice).message())
            .isEqualTo("OpenRouter has no API key login. Omit --api-key to sign in.");
    }

    @Test
    void providerWithoutALoginIsConfiguredOutside() {
        var provider = new StubProvider("local-model", "Local Model");

        var choice = AuthLoginCommand.selectAuthType(provider, false);

        assertThat(choice).isInstanceOf(AuthLoginCommand.AuthChoice.Unavailable.class);
        assertThat(((AuthLoginCommand.AuthChoice.Unavailable) choice).message())
            .isEqualTo("Local Model is configured outside pi-java (ambient credentials).");
    }

    /** Minimal provider with neither OAuth nor an API-key login. */
    static final class StubProvider implements com.pijava.ai.provider.Provider {
        private final String name;
        private final String displayName;

        StubProvider(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        @Override
        public java.util.Set<Class<? extends com.pijava.ai.api.ProviderApi>> supportedApis() {
            return java.util.Set.of();
        }

        @Override
        public <T extends com.pijava.ai.api.ProviderApi> T createApi(
                Class<T> apiType, com.pijava.ai.api.ApiOptions options) {
            throw new UnsupportedOperationException("stub provider");
        }

        @Override
        public com.pijava.ai.catalog.ModelCatalog builtinModels() {
            return com.pijava.ai.catalog.ModelCatalog.empty();
        }
    }

    @Test
    void loginCancellationReportsTheCancellation() {
        var terminal = new ScriptedTerminal(true).failSecret();
        var code = AuthLoginCommand.run(
            new String[] {"deepseek", "--api-key"}, loginOptions(terminal));

        assertThat(code).isEqualTo(1);
        assertThat(terminal.errors()).containsExactly("Login cancelled.");
    }

    // ── auth logout ─────────────────────────────────────────

    @Test
    void logoutUnknownProviderWithoutCredentialsReportsBothLines() {
        var terminal = new ScriptedTerminal(false);
        var code = AuthLogoutCommand.run(new String[] {"nowhere"}, logoutOptions(terminal));

        assertThat(code).isEqualTo(1);
        assertThat(terminal.errors()).containsExactly(
            "Unknown provider \"nowhere\".",
            "Stored credentials: none");
    }

    @Test
    void logoutWithoutStoredCredentialsExitsZero() {
        var terminal = new ScriptedTerminal(false);
        var code = AuthLogoutCommand.run(new String[] {"DeepSeek"}, logoutOptions(terminal));

        assertThat(code).isZero();
        assertThat(terminal.output()).containsExactly(
            "No stored credentials for DeepSeek. Environment variables and models.json "
                + "config are unchanged.");
    }

    @Test
    void logoutRemovesAnOAuthCredential() {
        var store = new OAuthCredentialStore(tmp.resolve("auth-oauth.json"));
        store.store("anthropic", OAuthCredential.permanent("token"));
        var terminal = new ScriptedTerminal(false);

        var code = AuthLogoutCommand.run(new String[] {"anthropic"}, logoutOptions(terminal));

        assertThat(code).isZero();
        assertThat(store.resolve("anthropic")).isEmpty();
        assertThat(terminal.output()).containsExactly("Logged out of Anthropic.");
    }

    @Test
    void logoutRemovesAnApiKeyAndItsProfiles() {
        var store = new FileCredentialStore(tmp.resolve("auth.json"));
        store.storeApiKey("deepseek", "sk-main");
        store.storeApiKey("deepseek", "work", "sk-profile");
        var terminal = new ScriptedTerminal(false);

        var code = AuthLogoutCommand.run(new String[] {"deepseek"}, logoutOptions(terminal));

        assertThat(code).isZero();
        assertThat(store.resolveApiKey("deepseek")).isEmpty();
        assertThat(store.resolveApiKey("deepseek", "work")).isEmpty();
        assertThat(terminal.output()).containsExactly(
            "Removed stored API key for DeepSeek. Environment variables and models.json "
                + "config are unchanged.");
    }

    /** Scripted terminal for the credential commands. */
    static final class ScriptedTerminal implements AuthTerminal {
        private final boolean interactive;
        private final Deque<String> secrets = new ArrayDeque<>();
        private final Deque<String> answers = new ArrayDeque<>();
        private boolean failSecrets;
        private final List<String> output = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> opened = new ArrayList<>();

        ScriptedTerminal(boolean interactive) {
            this.interactive = interactive;
        }

        ScriptedTerminal answerSecret(String secret) {
            secrets.add(secret);
            return this;
        }

        ScriptedTerminal answer(String answer) {
            answers.add(answer);
            return this;
        }

        ScriptedTerminal failSecret() {
            failSecrets = true;
            return this;
        }

        List<String> output() {
            return output;
        }

        List<String> errors() {
            return errors;
        }

        List<String> opened() {
            return opened;
        }

        @Override
        public boolean interactive() {
            return interactive;
        }

        @Override
        public void write(String line) {
            output.add(line);
        }

        @Override
        public void error(String line) {
            errors.add(line);
        }

        @Override
        public String question(String message) throws IOException {
            if (answers.isEmpty()) {
                throw new IOException("Login cancelled");
            }
            return answers.poll();
        }

        @Override
        public String secret(String message) throws IOException {
            if (failSecrets || secrets.isEmpty()) {
                throw new IOException("Login cancelled");
            }
            return secrets.poll();
        }

        @Override
        public void openUrl(String url) {
            opened.add(url);
        }
    }
}
