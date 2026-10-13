package com.pijava.ai.provider;

/**
 * Anthropic provider — Claude models via the Anthropic Messages API.
 */
public final class AnthropicProvider extends AnthropicCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "anthropic", "Anthropic", "https://api.anthropic.com",
            "ANTHROPIC_API_KEY", Protocol.ANTHROPIC_MESSAGES,
            BuiltinModelCatalogs.catalog("anthropic"));
    }
}
