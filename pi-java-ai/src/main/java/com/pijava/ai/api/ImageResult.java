package com.pijava.ai.api;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

/**
 * Image generation result — aligned with pi {@code AssistantImages}.
 *
 * @param provider     provider id (e.g. "openrouter-images")
 * @param model        the model that generated the output
 * @param output       generated text and/or image content blocks
 * @param stopReason   how generation ended
 * @param errorMessage failure message, or {@code null} on success
 * @param timestamp    Unix epoch milliseconds
 */
public record ImageResult(
    String provider,
    String model,
    List<ContentBlock> output,
    ImageStopReason stopReason,
    String errorMessage,
    long timestamp
) {
    /** Defensively copies {@code output}. */
    public ImageResult {
        output = List.copyOf(output);
    }
}
