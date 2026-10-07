package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Storage and interaction hooks the OAuth flow drives (pi flow.ts:47-68).
 *
 * <p>pi allows every method to return a promise; here they are synchronous, so
 * a provider must do its work before returning. That is a deliberate deviation
 * from pi's {@code MaybePromise} shape.</p>
 */
public interface OAuthClientProvider {

    /** Redirect URI this client is registered with. */
    String redirectUrl();

    /** Client metadata used for dynamic registration. */
    OAuthClientMetadata clientMetadata();

    /** A Client ID Metadata Document: an https URL used as {@code client_id}. */
    interface ClientMetadataDocument {

        /** The document URL used as {@code client_id}. */
        String url();

        /** Redirect URI the document lists, which may differ from ours. */
        String redirectUrl();
    }

    /** Replaces the built-in client authentication methods. */
    @FunctionalInterface
    interface AddClientAuthentication {

        /** Add credentials to the token request. */
        void apply(Map<String, String> headers, Map<String, String> params, URI url,
                   @Nullable AuthorizationServerMetadata metadata) throws Exception;
    }

    /**
     * Identify as a metadata document instead of registering. Only consulted
     * when no client information is stored; {@code metadata} is null when the
     * authorization server has none.
     */
    default @Nullable ClientMetadataDocument clientMetadataDocument(
            @Nullable AuthorizationServerMetadata metadata) {
        return null;
    }

    /** CSRF state for the next authorization request, optionally persisted. */
    default @Nullable String state() {
        return null;
    }

    /** Stored client identity, if any. */
    @Nullable OAuthClientInformation clientInformation();

    /** Whether {@link #saveClientInformation} is implemented. */
    default boolean canSaveClientInformation() {
        return false;
    }

    /** Persist a registration result. */
    default void saveClientInformation(OAuthClientInformation information) {
    }

    /** Stored tokens, if any. */
    @Nullable OAuthTokens tokens();

    /** Persist tokens. */
    void saveTokens(OAuthTokens tokens);

    /** Send the user to the authorization page. */
    void redirectToAuthorization(URI url) throws Exception;

    /** Persist the PKCE verifier for the pending authorization. */
    default void saveCodeVerifier(String verifier) {
    }

    /** The PKCE verifier of the pending authorization. */
    String codeVerifier();

    /** Custom client authentication, or null to use the built-in methods. */
    default @Nullable AddClientAuthentication addClientAuthentication() {
        return null;
    }

    /** Drop stored credentials: {@code all}, {@code client}, {@code tokens}, {@code verifier}, or {@code discovery}. */
    default void invalidateCredentials(String kind) {
    }

    /** Persist a discovery result. */
    default void saveDiscoveryState(OAuthDiscoveryState state) {
    }

    /** Cached discovery result, if any. */
    default @Nullable OAuthDiscoveryState discoveryState() {
        return null;
    }
}
