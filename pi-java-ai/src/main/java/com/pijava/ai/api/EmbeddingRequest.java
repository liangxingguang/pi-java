package com.pijava.ai.api;

import java.util.List;

import com.pijava.ai.model.ModelId;

/**
 * Text embedding request.
 *
 * @param model the embedding model id
 * @param input the texts to embed
 */
public record EmbeddingRequest(
    ModelId<?> model,
    List<String> input
) {
    /** Defensively copies {@code input}. */
    public EmbeddingRequest {
        input = List.copyOf(input);
    }
}
