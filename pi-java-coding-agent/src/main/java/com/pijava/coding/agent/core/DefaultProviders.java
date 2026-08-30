package com.pijava.coding.agent.core;

import com.pijava.agent.harness.StreamFn;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.auth.Credentials;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Assembly helpers for providers and the harness {@link StreamFn}
 * (Phase 3 design §9.5 "新增 API 清单").
 */
public final class DefaultProviders {

    private static final String DEFAULT_PROVIDER = "google";

    private DefaultProviders() {}

    /**
     * Register the 16 built-in providers, any ServiceLoader-discovered
     * third-party {@code ProviderFactory} implementations, and the user's
     * {@code models.json} custom providers (which override builtins on
     * name collision). A malformed models.json warns and continues —
     * aligned with pi's diagnostics behavior.
     */
    public static ProviderRegistry defaultProviders() {
        var registry = ProviderRegistry.create();
        registry.loadBuiltinProviders();
        registry.discoverFromServiceLoader();
        registerModelsJsonProviders(registry);
        return registry;
    }

    private static void registerModelsJsonProviders(ProviderRegistry registry) {
        try {
            for (var provider : com.pijava.ai.provider.ModelsJsonConfig.loadDefault().buildProviders()) {
                registry.register(provider);
            }
        } catch (RuntimeException e) {
            System.err.println("Warning: failed to load models.json providers: "
                + e.getMessage());
        }
    }

    /**
     * Resolve the effective provider name: CLI {@code --provider} &gt; settings
     * {@code defaultProvider} &gt; the built-in default ("google").
     */
    public static String resolveProviderName(Args args, String defaultProvider) {
        if (args.provider() != null && !args.provider().isBlank()) {
            return args.provider();
        }
        if (defaultProvider != null && !defaultProvider.isBlank()) {
            return defaultProvider;
        }
        return DEFAULT_PROVIDER;
    }

    /**
     * Build a {@link StreamFn} that routes through the provider selected by
     * {@code args.provider()} or the settings default, using the CLI API key,
     * the settings key, or the environment/file credential store.
     */
    public static StreamFn streamFnFor(Args args, String defaultProvider,
                                       ProviderRegistry providers, Settings settings) {
        var providerName = resolveProviderName(args, defaultProvider);
        return (messages, model, options) -> {
            var provider = providers.get(providerName)
                .orElseThrow(() -> new IllegalStateException("Unknown provider: " + providerName));
            return streamBlocking(provider, messages, model, options,
                apiOptions(args, providerName, settings, Credentials::resolveApiKey));
        };
    }

    private static StreamIterator streamBlocking(
            Provider provider,
            List<Message> messages,
            ModelId<?> model,
            com.pijava.agent.harness.StreamOptions options,
            ApiOptions apiOptions) {
        var api = provider.createApi(ChatApi.class, apiOptions);
        var extra = new java.util.LinkedHashMap<String, Object>();
        var thinking = options.thinking();
        if (thinking != null && thinking.enabled()
                && thinking.budgetTokens().isPresent()) {
            extra.put("thinking.budgetTokens", thinking.budgetTokens().getAsInt());
        }
        var request = new com.pijava.ai.api.StreamRequest(
            model, messages, options.tools(),
            options.maxTokens().orElse(-1),
            options.temperature().orElse(-1),
            extra);
        return api.streamBlocking(request, apiOptions);
    }

    /**
     * Resolve {@link ApiOptions} for a provider: baseUrl/apiKey priority is
     * CLI flag &gt; settings default &gt; credential resolver (null = none).
     */
    static ApiOptions apiOptions(Args args, String providerName, Settings settings,
                                 Function<String, Optional<String>> credentialResolver) {
        var baseUrl = firstNonBlank(args.baseUrl(), settings == null ? null : settings.defaultBaseUrl);
        var apiKey = firstNonBlank(args.apiKey(), settings == null ? null : settings.defaultApiKey);
        if (apiKey == null && credentialResolver != null) {
            apiKey = credentialResolver.apply(providerName).orElse(null);
        }
        return new ApiOptions(baseUrl == null ? "" : baseUrl,
            apiKey == null ? "" : apiKey,
            java.time.Duration.ofSeconds(120), 2, Map.of());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
