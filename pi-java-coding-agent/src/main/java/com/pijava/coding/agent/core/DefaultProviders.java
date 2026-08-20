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

/**
 * Assembly helpers for providers and the harness {@link StreamFn}
 * (Phase 3 design §9.5 "新增 API 清单").
 */
public final class DefaultProviders {

    private static final String DEFAULT_PROVIDER = "google";

    private DefaultProviders() {}

    /**
     * Register the 16 built-in providers and any ServiceLoader-discovered
     * third-party {@code ProviderFactory} implementations.
     */
    public static ProviderRegistry defaultProviders() {
        var registry = ProviderRegistry.create();
        registry.loadBuiltinProviders();
        registry.discoverFromServiceLoader();
        return registry;
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
     * {@code args.provider()} or the settings default, using the CLI API key or
     * the environment/file credential store.
     */
    public static StreamFn streamFnFor(Args args, String defaultProvider,
                                       ProviderRegistry providers) {
        var providerName = resolveProviderName(args, defaultProvider);
        return (messages, model, options) -> {
            var provider = providers.get(providerName)
                .orElseThrow(() -> new IllegalStateException("Unknown provider: " + providerName));
            return streamBlocking(provider, messages, model, options, apiOptions(args, providerName));
        };
    }

    private static StreamIterator streamBlocking(
            Provider provider,
            List<Message> messages,
            ModelId<?> model,
            com.pijava.agent.harness.StreamOptions options,
            ApiOptions apiOptions) {
        var api = provider.createApi(ChatApi.class, apiOptions);
        var request = new com.pijava.ai.api.StreamRequest(
            model, messages, options.tools(),
            options.maxTokens().orElse(-1),
            options.temperature().orElse(-1),
            Map.of());
        return api.streamBlocking(request, apiOptions);
    }

    private static ApiOptions apiOptions(Args args, String providerName) {
        var apiKey = resolveApiKey(args, providerName);
        return new ApiOptions("", apiKey,
            java.time.Duration.ofSeconds(120), 2, Map.of());
    }

    private static String resolveApiKey(Args args, String providerName) {
        if (args.apiKey() != null && !args.apiKey().isBlank()) {
            return args.apiKey();
        }
        // P6-18：profile 感知解析（激活 profile → 默认）。
        return Credentials.resolveApiKey(providerName).orElse("");
    }
}
