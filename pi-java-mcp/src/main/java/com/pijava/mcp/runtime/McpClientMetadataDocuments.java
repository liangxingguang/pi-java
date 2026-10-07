package com.pijava.mcp.runtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.oauth.AuthorizationServerMetadata;
import com.pijava.mcp.oauth.OAuthClientProvider.ClientMetadataDocument;

/**
 * pi's Client ID Metadata Document, used with {@code oauth.clientRegistration: "cimd"}
 * (pi {@code oauth.ts:228-260}).
 *
 * <p>Note that the document lives under {@code https://pi.dev/oauth}, a host pi operates and
 * pi-java does not, so the mode cannot complete against a real authorization server here.</p>
 */
final class McpClientMetadataDocuments {

    /** Where pi.dev serves pi's metadata documents ({@code oauth.ts:42}). */
    static final String CLIENT_METADATA_BASE_URL = "https://pi.dev/oauth";

    private McpClientMetadataDocuments() {
    }

    /**
     * Twelve characters identifying an MCP server URL in callback paths, computed the way Codex
     * does it ({@code oauth.ts:228-232}).
     *
     * @param serverUrl the MCP server URL
     * @return nine base64url-encoded bytes of the SHA-256 of the fragment-less href
     */
    static String callbackId(String serverUrl) {
        var normalized = WebUrls.normalizeWithoutFragment(serverUrl);
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 9));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    /**
     * The document to identify with ({@code oauth.ts:240-260}).
     *
     * <p>Without the {@code iss} parameter in authorization responses (RFC 9207), the redirect
     * URI and the document are specific to the MCP server, so a response cannot be mixed up with
     * one from another authorization server (RFC 9700 section 4.4.2.2).</p>
     *
     * @param serverUrl the MCP server URL
     * @param redirectUrl the redirect URI the flow picked
     * @param metadata the authorization server's metadata, when it has any
     * @return the document URL and the redirect URI it lists
     */
    static ClientMetadataDocument create(
            String serverUrl, String redirectUrl, @Nullable AuthorizationServerMetadata metadata) {
        if (metadata == null
                || !Boolean.TRUE.equals(metadata.clientIdMetadataDocumentSupported())
                || metadata.tokenEndpointAuthMethodsSupported() == null
                || !metadata.tokenEndpointAuthMethodsSupported().contains("none")) {
            throw new IllegalStateException("The authorization server does not support Client ID Metadata"
                    + " Documents for public clients; remove oauth.clientRegistration \"cimd\"");
        }
        if (Boolean.TRUE.equals(metadata.authorizationResponseIssParameterSupported())) {
            return new Document(CLIENT_METADATA_BASE_URL + "/client.json", redirectUrl);
        }
        var id = callbackId(serverUrl);
        var redirect = WebUrls.withPath(URI.create(redirectUrl),
                McpCallbackSettings.CALLBACK_PATH + "/" + id);
        return new Document(CLIENT_METADATA_BASE_URL + "/" + id + "/client.json", redirect);
    }

    /** One metadata document ({@code OAuthClientProvider.ClientMetadataDocument}). */
    private record Document(String url, String redirectUrl) implements ClientMetadataDocument {
    }
}
