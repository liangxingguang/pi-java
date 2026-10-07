package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

/**
 * Result of discovering the authorization server behind an MCP server
 * (pi types.ts:77-81).
 *
 * @param authorizationServerUrl chosen authorization server URL
 * @param authorizationServerMetadata its metadata document, if found
 * @param resourceMetadata the MCP server's protected-resource metadata, if found
 */
public record OAuthServerInfo(
        String authorizationServerUrl,
        @Nullable AuthorizationServerMetadata authorizationServerMetadata,
        @Nullable OAuthProtectedResourceMetadata resourceMetadata) {
}
