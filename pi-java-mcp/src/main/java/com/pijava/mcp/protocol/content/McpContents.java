package com.pijava.mcp.protocol.content;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;

import com.pijava.mcp.McpJson;

/**
 * Conversion of MCP tool results to model-facing content
 * ({@code content.ts:78-117}).
 */
public final class McpContents {

    private McpContents() {
    }

    /** Convert one content block ({@code content.ts:78-102}). */
    private static LlmContent blockToLlmContent(McpContentBlock block) {
        return switch (block) {
            case McpContentBlock.Text b -> new LlmContent.Text(b.text());
            case McpContentBlock.Image b -> new LlmContent.Image(b.data(), b.mimeType());
            case McpContentBlock.Audio b -> new LlmContent.Text("[audio " + b.mimeType() + " omitted]");
            case McpContentBlock.ResourceLink b -> new LlmContent.Text(b.name() + ": " + b.uri());
            case McpContentBlock.Embedded b -> switch (b.resource()) {
                case ResourceContents.Text r -> new LlmContent.Text(r.text());
                case ResourceContents.Blob r when r.mimeType() != null && r.mimeType().startsWith("image/") ->
                        new LlmContent.Image(r.blob(), r.mimeType());
                case ResourceContents.Blob r -> new LlmContent.Text(
                        "[binary resource " + r.uri() + " ("
                                + (r.mimeType() == null ? "unknown type" : r.mimeType()) + ") omitted]");
            };
        };
    }

    /**
     * Convert a tool result to text and image content for a model
     * ({@code content.ts:111-117}). A result without content blocks but with
     * {@code structuredContent} becomes its pretty-printed JSON.
     */
    public static List<LlmContent> toLlmContent(CallToolResult result) {
        var blocks = result.content() == null ? List.<McpContentBlock>of() : result.content();
        var content = blocks.stream()
                .map(McpContents::blockToLlmContent)
                .collect(Collectors.toList());
        if (content.isEmpty() && result.structuredContent() != null) {
            try {
                content.add(new LlmContent.Text(McpJson.mapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result.structuredContent())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
        return content;
    }
}
