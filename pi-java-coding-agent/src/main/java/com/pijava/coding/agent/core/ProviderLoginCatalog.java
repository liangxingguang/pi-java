package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.pijava.ai.auth.Credentials;
import com.pijava.ai.auth.OAuthProviders;
import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;

/** Builds login choices from provider and authentication metadata. */
public final class ProviderLoginCatalog {

    private static final Set<String> SUBSCRIPTIONS = Set.of(
        "anthropic", "openai", "openai-codex", "github-copilot", "kimi", "xai");

    private ProviderLoginCatalog() {}

    /** Return all account and API-key choices, sorted by display name. */
    public static List<LoginProviderOption> options(ProviderRegistry providers) {
        return options(providers, Credentials::resolveCredential);
    }

    static List<LoginProviderOption> options(
            ProviderRegistry providers,
            Function<String, Optional<RecordedCredential>> credentials) {
        var options = new ArrayList<LoginProviderOption>();
        for (var provider : providers.listAll()) {
            var status = credentials.apply(provider.name())
                .map(RecordedCredential::source).orElse(null);
            if (OAuthProviders.get(provider.name()).isPresent()) {
                options.add(new LoginProviderOption(provider.name(), provider.displayName(),
                    LoginAuthType.OAUTH, status, SUBSCRIPTIONS.contains(provider.name())));
            }
            var configurable = configurable(provider);
            if (configurable != null && configurable.providerConfig().apiKeyEnvVar() != null) {
                options.add(new LoginProviderOption(provider.name(), provider.displayName(),
                    LoginAuthType.API_KEY, status, false));
            }
        }
        options.sort(Comparator.comparing((LoginProviderOption option) ->
                option.name().toLowerCase(Locale.ROOT))
            .thenComparing(LoginProviderOption::id)
            .thenComparing(LoginProviderOption::authType));
        return List.copyOf(options);
    }

    /** Find exact provider id or display-name matches, ignoring case and surrounding space. */
    public static List<LoginProviderOption> find(
            List<LoginProviderOption> options, String providerReference) {
        if (providerReference == null) {
            return List.of();
        }
        var reference = providerReference.trim().toLowerCase(Locale.ROOT);
        if (reference.isEmpty()) {
            return List.of();
        }
        return options.stream().filter(option ->
            option.id().toLowerCase(Locale.ROOT).equals(reference)
                || option.name().toLowerCase(Locale.ROOT).equals(reference)).toList();
    }

    private static ConfigurableProvider configurable(Provider provider) {
        if (provider instanceof ConfigurableProvider configurable) {
            return configurable;
        }
        if (provider instanceof RemoteCatalogProvider remote
                && remote.delegate() instanceof ConfigurableProvider configurable) {
            return configurable;
        }
        return null;
    }
}
