package com.pijava.ai.api;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;

/**
 * Image generation request — aligned with pi {@code ImagesContext} whose
 * {@code input} is a list of text/image content blocks.
 *
 * @param model the image-capable model id
 * @param input prompt input: text and/or image content blocks
 */
public record ImageRequest(
    ModelId<?> model,
    List<ContentBlock> input
) {
    /** Defensively copies {@code input}. */
    public ImageRequest {
        input = List.copyOf(input);
    }
}
