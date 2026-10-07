package com.pijava.mcp.protocol.content;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Annotations on content ({@code content.ts:1-5}).
 *
 * @param audience     intended audience
 * @param priority     priority
 * @param lastModified last-modified timestamp
 */
public record ContentAnnotations(
        @Nullable List<String> audience,
        @Nullable Double priority,
        @Nullable String lastModified) {
}
