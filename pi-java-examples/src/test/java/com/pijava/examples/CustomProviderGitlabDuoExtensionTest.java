package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.agent.skill.SkillManager;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.provider.Protocol;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContext;

import org.junit.jupiter.api.Test;

class CustomProviderGitlabDuoExtensionTest {

    private static ExtensionContext context(ProviderRegistry registry) {
        var services = new SessionServices(null, null, registry, null, null, null, null, null);
        return new DefaultExtensionContext(services, new SkillManager());
    }

    @Test
    void registersGitlabDuoProviderWithPerModelProtocols() {
        var extension = new CustomProviderGitlabDuoExtension();
        var registry = ProviderRegistry.create();
        extension.register(context(registry).forExtension(extension.name()));

        assertThat(extension.name()).isEqualTo("custom-provider-gitlab-duo");
        Provider provider = registry.get("gitlab-duo").orElseThrow();
        var config = ((ConfigurableProvider) provider).providerConfig();
        assertThat(config.name()).isEqualTo("gitlab-duo");
        assertThat(config.apiKeyEnvVar()).isEqualTo("GITLAB_TOKEN");
        assertThat(config.defaultBaseUrl()).isEqualTo("https://cloud.gitlab.com");
        assertThat(provider.supportedProtocols())
                .containsExactlyInAnyOrder(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES);
        assertThat(provider.getModels()).hasSize(9);
        assertThat(provider.getModels()).extracting(info -> info.id().modelName())
                .contains("claude-sonnet-4-5-20250929", "gpt-5-codex");
    }

    @Test
    void selectsProtocolAndBaseUrlByModelId() {
        assertThat(CustomProviderGitlabDuoExtension.protocolFor("claude-sonnet-4-5-20250929"))
                .isEqualTo(Protocol.ANTHROPIC_MESSAGES);
        assertThat(CustomProviderGitlabDuoExtension.protocolFor("gpt-5-codex"))
                .isEqualTo(Protocol.OPENAI_RESPONSES);
        assertThat(CustomProviderGitlabDuoExtension
                .baseUrlFor("claude-sonnet-4-5-20250929"))
                .contains("https://cloud.gitlab.com/ai/v1/proxy/anthropic/");
        assertThat(CustomProviderGitlabDuoExtension.baseUrlFor("gpt-5-codex"))
                .contains("https://cloud.gitlab.com/ai/v1/proxy/openai/v1");
        assertThatThrownBy(() -> CustomProviderGitlabDuoExtension.protocolFor("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown model");
    }

    @Test
    void oauthShapeIsDataOnly() {
        String url = CustomProviderGitlabDuoExtension.OAuth
                .authorizeUrl("challenge-1", "state-1");
        assertThat(url).startsWith("https://gitlab.com/oauth/authorize?")
                .contains("client_id=")
                .contains("code_challenge=challenge-1")
                .contains("code_challenge_method=S256")
                .contains("state=state-1");

        var credential = CustomProviderGitlabDuoExtension.OAuth
                .credentials("access", "refresh", 1_000L, 3600L);
        assertThat(credential.accessToken()).isEqualTo("access");
        assertThat(credential.refreshToken()).isEqualTo("refresh");
        assertThat(credential.expiresAtEpochSec()).isEqualTo(1_000L + 3_600L - 300L);
        assertThat(credential.baseUrl()).isEqualTo("https://gitlab.com");
    }
}
