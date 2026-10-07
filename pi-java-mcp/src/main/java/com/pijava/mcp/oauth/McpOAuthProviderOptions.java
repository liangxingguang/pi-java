package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.oauth.OAuthClientProvider.ClientMetadataDocument;

/**
 * Options of an {@link McpOAuthProvider} (pi provider.ts:26-38).
 *
 * @param serverUrl the MCP server this provider serves
 * @param redirectUrl redirect URI this client is registered with
 * @param clientMetadata document to register; {@code redirectUris} may be
 *                       omitted and defaults to the redirect URL
 * @param clientMetadataDocument identifies as a metadata document instead of registering
 * @param clientId preconfigured client id, skipping registration
 * @param clientSecret preconfigured client secret
 * @param store durable state store; in-memory when null
 * @param onRedirect sends the user to the authorization page
 */
public record McpOAuthProviderOptions(
        URI serverUrl,
        URI redirectUrl,
        OAuthClientMetadata clientMetadata,
        @Nullable Function<@Nullable AuthorizationServerMetadata,
                @Nullable ClientMetadataDocument> clientMetadataDocument,
        @Nullable String clientId,
        @Nullable String clientSecret,
        @Nullable OAuthStateStore store,
        OnRedirect onRedirect) {

    /** Sends the user to the authorization page. */
    @FunctionalInterface
    public interface OnRedirect {

        /** Open the authorization URL. */
        void redirect(URI url) throws Exception;
    }
}
