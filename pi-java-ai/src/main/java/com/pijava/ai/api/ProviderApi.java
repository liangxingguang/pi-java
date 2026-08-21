package com.pijava.ai.api;

/**
 * Marker interface for an API capability exposed by a {@link com.pijava.ai.provider.Provider}.
 *
 * <p>Each concrete subtype represents a distinct API modality (chat, image generation,
 * embeddings). Phase 1 only defined {@link ChatApi}; P6-28 adds {@link ImageApi}
 * (aligned with pi's {@code ImagesFunction}) and {@link EmbeddingApi}.</p>
 *
 * <p>This is a sealed interface following the Erasable Java convention — the set of
 * allowed API types is fixed at compile time and exhaustive {@code switch} coverage
 * is enforced by the compiler.</p>
 *
 * @see com.pijava.ai.provider.Provider#supportedApis()
 * @see com.pijava.ai.provider.Provider#createApi(Class, ApiOptions)
 */
public sealed interface ProviderApi
        permits ChatApi, ImageApi, EmbeddingApi {
}
