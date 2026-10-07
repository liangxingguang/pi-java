package com.pijava.mcp.oauth;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * RFC 9728 protected-resource metadata (pi types.ts:9-14). Only the fields pi
 * names are typed; other document fields are kept verbatim in {@code extension}.
 *
 * @param resource canonical resource identifier this document describes
 * @param authorizationServers authorization servers serving the resource
 * @param scopesSupported scopes the resource understands
 * @param extension all other document fields
 */
public record OAuthProtectedResourceMetadata(
        String resource,
        @Nullable List<String> authorizationServers,
        @Nullable List<String> scopesSupported,
        @Nullable Map<String, Object> extension) {
}
