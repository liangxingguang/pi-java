package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.protocol.AnthropicMessagesApi;
import com.pijava.ai.protocol.OpenAICompletionsApi;
import com.pijava.ai.provider.ModelsJsonSchema.ProviderDef;

/**
 * A user-defined Provider loaded from {@code ~/.pi-java/agent/models.json}
 * (pi-compatible schema), served by one of the shared protocol adapters.
 *
 * <p>The inline {@code apiKey} (if any) is injected into empty
 * {@link ApiOptions} — CLI/settings/credential-store keys keep priority in
 * the normal resolution chain; this is the last-resort fallback.</p>
 */
public final class ModelsJsonProvider extends ConfigurableProvider {

    private final ProviderConfig config;
    private final String inlineApiKey;

    ModelsJsonProvider(String id, String displayName, ProviderDef def,
                       Protocol protocol, ModelCatalog catalog) {
        this.config = ProviderConfig.single(
            id, displayName, def.baseUrl(), null, protocol, catalog);
        this.inlineApiKey = def.apiKey();
    }

    @Override
    protected ProviderConfig config() {
        return config;
    }

    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        // settings.defaultBaseUrl is a global override meant for built-in
        // providers pointing at relays; a models.json provider's baseUrl is
        // explicit user config and must win over it (CLI --base-url still
        // applies because DefaultProviders passes it through options.baseUrl —
        // indistinguishable here, accepted tradeoff).
        var pinned = new ApiOptions(config.defaultBaseUrl(), options.apiKey(),
            options.timeout(), options.maxRetries(), options.extra());
        return super.createApi(apiType, withInlineKey(pinned));
    }

    private ApiOptions withInlineKey(ApiOptions options) {
        if (inlineApiKey == null || inlineApiKey.isBlank()
                || (options.apiKey() != null && !options.apiKey().isBlank())) {
            return options;
        }
        return new ApiOptions(options.baseUrl(), inlineApiKey,
            options.timeout(), options.maxRetries(), options.extra());
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            // null envVar → AbstractChatApi falls back to a placeholder key
            // when nothing is configured (Ollama-style local endpoints).
            case OPENAI_COMPLETIONS -> new OpenAICompletionsApi(options, null);
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesApi(options, null);
            default -> throw new IllegalArgumentException(
                "models.json provider \"" + name() + "\": protocol " + protocol.wireName()
                + " is not supported yet (openai-completions / anthropic-messages only)");
        };
    }
}
