package com.pijava.mcp.protocol.content;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Contents of a resource: text or base64 blob ({@code content.ts:42-54}).
 *
 * <p>No {@code type} field exists on the wire; the variant is deduced from the
 * presence of {@code text} or {@code blob}.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ResourceContents.Text.class),
        @JsonSubTypes.Type(value = ResourceContents.Blob.class)
})
public sealed interface ResourceContents permits ResourceContents.Text, ResourceContents.Blob {

    /** Text resource contents. */
    record Text(String uri, @Nullable String mimeType, String text,
                @JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ResourceContents {
    }

    /** Base64 binary resource contents. */
    record Blob(String uri, @Nullable String mimeType, String blob,
                @JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ResourceContents {
    }
}
