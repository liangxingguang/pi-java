package com.pijava.ai.api;

import java.util.List;

/**
 * Text embedding result.
 *
 * @param model      the embedding model used
 * @param embeddings one vector per input string (same order)
 * @param inputTokens total prompt tokens consumed
 */
public record EmbeddingResult(
    String model,
    List<float[]> embeddings,
    int inputTokens
) {
    /** Defensively copies {@code embeddings} (each vector is mutable). */
    public EmbeddingResult {
        embeddings = List.copyOf(embeddings);
    }
}
