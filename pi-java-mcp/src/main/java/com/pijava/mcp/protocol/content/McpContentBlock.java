package com.pijava.mcp.protocol.content;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One content block of a tool result ({@code content.ts:7-63}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = McpContentBlock.Text.class, name = "text"),
        @JsonSubTypes.Type(value = McpContentBlock.Image.class, name = "image"),
        @JsonSubTypes.Type(value = McpContentBlock.Audio.class, name = "audio"),
        @JsonSubTypes.Type(value = McpContentBlock.ResourceLink.class, name = "resource_link"),
        @JsonSubTypes.Type(value = McpContentBlock.Embedded.class, name = "resource")
})
public sealed interface McpContentBlock permits McpContentBlock.Text, McpContentBlock.Image,
        McpContentBlock.Audio, McpContentBlock.ResourceLink, McpContentBlock.Embedded {

    /** Text block. */
    record Text(String text, @Nullable ContentAnnotations annotations,
                @JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpContentBlock {
    }

    /** Base64 image block. */
    record Image(String data, String mimeType, @Nullable ContentAnnotations annotations,
                 @JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpContentBlock {
    }

    /** Base64 audio block. */
    record Audio(String data, String mimeType, @Nullable ContentAnnotations annotations,
                 @JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpContentBlock {
    }

    /** Link to a resource. */
    record ResourceLink(String uri, String name, @Nullable String title, @Nullable String description,
                        @Nullable String mimeType, @Nullable Double size,
                        @Nullable ContentAnnotations annotations,
                        @JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpContentBlock {
    }

    /** Embedded resource. */
    record Embedded(ResourceContents resource, @Nullable ContentAnnotations annotations,
                    @JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpContentBlock {
    }
}
