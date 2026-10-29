package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.provider.builtin.RadiusProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderLoginCatalogTest {

    @Test
    void providerReferenceMatchesIdOrNameCaseInsensitively() {
        var options = List.of(ProviderLoginFixtures.oauth("radius", "Radius"));

        assertThat(ProviderLoginCatalog.find(options, "  rAdIuS ")).containsExactly(options.getFirst());
        assertThat(ProviderLoginCatalog.find(options, " radius ")).hasSize(1);
        assertThat(ProviderLoginCatalog.find(options, "other")).isEmpty();
        assertThat(ProviderLoginCatalog.find(options, "  ")).isEmpty();
    }

    @Test
    void wrappedConfigurableProviderStillOffersApiKeyAndOAuth() {
        var registry = ProviderRegistry.create();
        registry.register(RemoteCatalogProvider.wrap(
            new RadiusProvider(), null, Optional.empty()));

        var options = ProviderLoginCatalog.options(registry, ignored -> Optional.empty());

        assertThat(options).extracting(LoginProviderOption::authType)
            .containsExactly(LoginAuthType.OAUTH, LoginAuthType.API_KEY);
        assertThat(options).allSatisfy(option -> {
            assertThat(option.id()).isEqualTo("radius");
            assertThat(option.name()).isEqualTo("Radius");
            assertThat(option.status()).isNull();
        });
    }
}
