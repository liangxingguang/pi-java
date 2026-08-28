package com.pijava.agent.harness;

import com.pijava.ai.message.ContentBlock;

/**
 * A base64 inline image attached to a prompt.
 *
 * <p>Aligned with pi's {@code ImageContent} (ai/types.ts:354-358): base64
 * only. Validation happens at construction, so every entry point (run /
 * steer / followUp / nextRun) rejects malformed images before anything
 * enters the transcript.</p>
 *
 * @param mimeType image MIME type (must start with "image/")
 * @param data     base64-encoded image bytes (no data: URL prefix)
 */
public record PromptImage(String mimeType, String data) {

    public PromptImage {
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new IllegalArgumentException(
                "mimeType must start with 'image/': " + mimeType);
        }
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("data must be non-empty");
        }
    }

    public ContentBlock.ImageContent toContentBlock() {
        return new ContentBlock.ImageContent(mimeType, data);
    }
}
