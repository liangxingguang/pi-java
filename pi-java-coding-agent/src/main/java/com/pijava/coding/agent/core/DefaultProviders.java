package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.auth.EnvApiKeyResolver;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.AnthropicProvider;
import com.pijava.ai.provider.DeepSeekProvider;
import com.pijava.ai.provider.GoogleProvider;
import com.pijava.ai.provider.MistralProvider;
import com.pijava.ai.provider.OpenAIProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.agent.harness.StreamFn;
import com.pijava.coding.agent.cli.Args;

/**
 * Assembly helpers for providers and the harness {@link StreamFn}
 * (Phase 3 design §9.5 "新增 API 清单").
 */
public final class DefaultProviders {

    private static final String DEFAULT_PROVIDER = "google";

    private DefaultProviders() {}

    /** Register the 5 built-in providers into a fresh registry. */
    public static ProviderRegistry defaultProviders() {
        var registry = ProviderRegistry.create();
        for (var provider : builtins()) {
            registry.register(provider);
        }
        return registry;
    }

    /**
     * Build a {@link StreamFn} that routes through the provider selected by
     * {@code args.provider()} (default "google"), using the CLI API key or the
     * environment/file credential store.
     */
    public static StreamFn streamFnFor(Args args, ProviderRegistry providers) {
        var providerName = args.provider() != null ? args.provider() : DEFAULT_PROVIDER;
        return (messages, model, options) -> {
            var provider = providers.get(providerName)
                .orElseThrow(() -> new IllegalStateException(
                    "Unknown provider: " + providerName));
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
        var env = new EnvApiKeyResolver().resolveApiKey(providerName);
        if (env.isPresent()) {
            return env.get();
        }
        return new FileCredentialStore().resolveApiKey(providerName)
            .orElse("");
    }

    private static List<Provider> builtins() {
        return List.of(
            new AnthropicProvider(),
            new OpenAIProvider(),
            new GoogleProvider(),
            new DeepSeekProvider(),
            new MistralProvider());
    }
}
