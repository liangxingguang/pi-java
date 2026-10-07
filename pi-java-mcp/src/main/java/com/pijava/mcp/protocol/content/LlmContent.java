package com.pijava.mcp.protocol.content;

/**
 * Tool result content in the shape LLM APIs accept: text and base64 images
 * ({@code content.ts:76}).
 */
public sealed interface LlmContent permits LlmContent.Text, LlmContent.Image {

    /** Text content. */
    record Text(String text) implements LlmContent {
    }

    /** Base64 image content. */
    record Image(String data, String mimeType) implements LlmContent {
    }
}
