package com.pijava.ai.api;

/**
 * Text embedding capability — pi-java 独有（pi 无 embedding provider）。
 *
 * <p>Converts input texts into dense vector embeddings via a provider's
 * embedding endpoint.</p>
 */
public non-sealed interface EmbeddingApi extends ProviderApi {

    /**
     * Embed a list of input strings.
     *
     * @param request the embedding request (model + input strings)
     * @param options API call options
     * @return per-input embedding vectors
     */
    EmbeddingResult embed(EmbeddingRequest request, ApiOptions options);
}
