package com.pijava.ai.model;

/**
 * Identifies a specific model from a provider.
 *
 * @param <P> the provider type marker (e.g. {@code AnthropicProvider})
 * @param provider  provider name (e.g. "anthropic", "openai")
 * @param modelName model identifier (e.g. "claude-fable-5")
 */
public record ModelId<P>(
    String provider,
    String modelName
) {
    /** Create a model ID without a provider type witness. */
    public static ModelId<?> of(String provider, String modelName) {
        return new ModelId<>(provider, modelName);
    }
}
