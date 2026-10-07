package com.pijava.mcp.runtime;

import java.net.URI;

import org.jspecify.annotations.Nullable;

/**
 * How a server's OAuth flow is configured (pi {@code oauth.ts:55-70}).
 *
 * <p>{@code clientSecret} arrives already resolved, because resolving it can fail and that
 * failure must only surface when a refresh needs it, not when the connection is set up.</p>
 *
 * @param clientId preconfigured client id, skipping registration
 * @param clientSecret resolved client secret
 * @param callbackPort port the loopback callback server prefers
 * @param callbackUrl loopback redirect URI as written in the configuration
 * @param scope scopes to request, space separated
 * @param clientName {@code client_name} for dynamic client registration
 * @param clientRegistration how the client identifies itself
 * @param authServerMetadataUrl metadata document to use instead of discovery
 */
public record McpOAuthSettings(
        @Nullable String clientId,
        @Nullable String clientSecret,
        @Nullable Integer callbackPort,
        @Nullable String callbackUrl,
        @Nullable String scope,
        @Nullable String clientName,
        @Nullable Registration clientRegistration,
        @Nullable URI authServerMetadataUrl) {

    /** How the client identifies itself to the authorization server ({@code mcp-servers.ts:130`}). */
    public enum Registration {

        /** Dynamic client registration. */
        DCR,

        /** A Client ID Metadata Document. */
        CIMD
    }

    /** Settings that configure nothing. */
    public static McpOAuthSettings none() {
        return new McpOAuthSettings(null, null, null, null, null, null, null, null);
    }

    /**
     * The registration mode a validated configuration string names.
     *
     * @param value {@code "dcr"}, {@code "cimd"}, or {@code null}
     * @return the mode, or {@code null} when the configuration left it out
     */
    public static @Nullable Registration registrationOf(@Nullable String value) {
        if (value == null) {
            return null;
        }
        return "cimd".equals(value) ? Registration.CIMD : Registration.DCR;
    }
}
