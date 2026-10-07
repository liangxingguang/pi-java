package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cached discovery result for one MCP server (pi types.ts:70-75).
 *
 * @param authorizationServerUrl chosen authorization server URL
 * @param authorizationServerMetadata its metadata document, if found
 * @param resourceMetadata the MCP server's protected-resource metadata, if found
 * @param resourceMetadataUrl explicitly configured metadata document URL
 */
public record OAuthDiscoveryState(
        @JsonProperty("authorizationServerUrl") String authorizationServerUrl,
        @JsonProperty("authorizationServerMetadata")
        @Nullable AuthorizationServerMetadata authorizationServerMetadata,
        @JsonProperty("resourceMetadata") @Nullable OAuthProtectedResourceMetadata resourceMetadata,
        @JsonProperty("resourceMetadataUrl") @Nullable String resourceMetadataUrl) {
}
