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

    /** How the client identifies itself to the authorization server ({@code mcp-servers.ts:130}). */
    public enum Registration {

        /**
         * The default: pi's Client ID Metadata Document when the authorization server
         * supports it for public clients, otherwise dynamic client registration. With
         * a {@code clientId}, a {@code clientName}, a renamed application, or a
         * {@code callbackUrl} other than {@code /callback} on localhost or 127.0.0.1,
         * always dynamic registration.
         */
        AUTO,

        /** Dynamic client registration. */
        DCR,

        /** A Client ID Metadata Document; sign-in fails without support. */
        CIMD
    }

    /** Settings that configure nothing. */
    public static McpOAuthSettings none() {
        return new McpOAuthSettings(null, null, null, null, null, null, null, null);
    }

    /**
     * The registration mode a validated configuration string names.
     *
     * @param value {@code "auto"}, {@code "dcr"}, {@code "cimd"}, or {@code null}
     * @return the mode, or {@code null} when the configuration left it out
     */
    public static @Nullable Registration registrationOf(@Nullable String value) {
        if (value == null) {
            return null;
        }
        if ("cimd".equals(value)) {
            return Registration.CIMD;
        }
        return "auto".equals(value) ? Registration.AUTO : Registration.DCR;
    }

    /**
     * Whether the flow should identify with a Client ID Metadata Document
     * ({@code oauth.ts:246-262}).
     *
     * <p>An absent {@code clientRegistration} means {@code auto}. A renamed
     * application ({@code appName} other than {@code pi}) always falls back to
     * dynamic registration, so pi-java never uses the document implicitly.</p>
     *
     * @param settings the OAuth configuration
     * @param appName the application name ({@code APP_NAME} in pi)
     * @return whether to offer the document
     */
    public static boolean offersClientMetadataDocument(McpOAuthSettings settings, String appName) {
        var registration = settings.clientRegistration() == null
                ? Registration.AUTO : settings.clientRegistration();
        if (registration != Registration.AUTO) {
            return registration == Registration.CIMD;
        }
        if (settings.clientId() != null || settings.clientName() != null || !"pi".equals(appName)) {
            return false;
        }
        return com.pijava.mcp.config.McpServerConfigs
                .isClientMetadataDocumentCallback(settings.callbackUrl());
    }
}
