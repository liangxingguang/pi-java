package com.pijava.mcp.oauth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pijava.mcp.JdkMcpFetch;
import com.pijava.mcp.McpFetch;
import com.pijava.mcp.McpJson;
import com.pijava.mcp.oauth.OAuthClientProvider.AddClientAuthentication;

/**
 * Token, registration, exchange and refresh requests
 * (pi flow.ts:186-275).
 */
final class OAuthTokenRequests {

    private OAuthTokenRequests() {
    }

    /**
     * Options shared by every token request.
     *
     * @param metadata authorization server metadata, when known
     * @param clientInformation the client identity to authenticate with
     * @param resource resource indicator to bind the grant to
     * @param addClientAuthentication custom client authentication
     * @param fetch HTTP fetch, defaulting to the JDK
     */
    record TokenRequestOptions(
            @Nullable AuthorizationServerMetadata metadata,
            OAuthClientInformation clientInformation,
            @Nullable String resource,
            @Nullable AddClientAuthentication addClientAuthentication,
            @Nullable McpFetch fetch) {
    }

    static McpFetch fetchOf(@Nullable McpFetch fetch) {
        return fetch == null ? new JdkMcpFetch() : fetch;
    }

    /** POST form parameters to the token endpoint (pi flow.ts:186-223). */
    static OAuthTokens tokenRequest(URI authorizationServerUrl, TokenRequestOptions options,
                                    Map<String, String> params) throws Exception {
        var endpoint = options.metadata() != null && options.metadata().tokenEndpoint() != null
                ? URI.create(options.metadata().tokenEndpoint())
                : authorizationServerUrl.resolve("/token");
        var url = OAuthEndpoints.secureEndpoint(endpoint);
        var headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("content-type", "application/x-www-form-urlencoded");
        if (options.resource() != null) {
            params.put("resource", options.resource());
        }
        var custom = options.addClientAuthentication();
        if (custom != null) {
            custom.apply(headers, params, url, options.metadata());
        } else {
            var metadata = options.metadata();
            var supported = metadata == null || metadata.tokenEndpointAuthMethodsSupported() == null
                    ? List.<String>of() : metadata.tokenEndpointAuthMethodsSupported();
            OAuthEndpoints.applyClientAuthentication(
                    OAuthEndpoints.selectClientAuthMethod(options.clientInformation(), supported),
                    options.clientInformation(), headers, params);
        }
        var body = OAuthEndpoints.formEncode(params).getBytes(StandardCharsets.UTF_8);
        var response = fetchOf(options.fetch())
                .fetch(new McpFetch.Request("POST", url, headers, body));
        var text = new String(response.body(), StandardCharsets.UTF_8);
        var value = parseOrNull(text);
        // Servers may report OAuth errors with any status, so check the body first.
        if (value instanceof Map<?, ?> map && map.get("error") instanceof String error) {
            var description = map.get("error_description") instanceof String described ? described : error;
            var errorUri = map.get("error_uri") instanceof String uri ? uri : null;
            throw new OAuthError(error, description, errorUri);
        }
        if (!response.ok()) {
            throw new OAuthError("server_error", "HTTP " + response.status() + ": " + text);
        }
        return OAuthMetadataParsers.parseOAuthTokens(value);
    }

    /** Register this client dynamically (pi flow.ts:225-247). */
    static OAuthClientInformation registerClient(URI authorizationServerUrl, Options options)
            throws IOException {
        var metadata = options.metadata();
        var endpoint = metadata == null ? null : metadata.registrationEndpoint();
        if (metadata != null && endpoint == null) {
            throw new IllegalStateException(
                    "Authorization server does not support dynamic client registration");
        }
        var url = endpoint != null ? URI.create(endpoint) : authorizationServerUrl.resolve("/register");
        var headers = Map.of(
                "Accept", "application/json",
                "content-type", "application/json");
        var payload = McpJson.mapper().convertValue(options.clientMetadata(),
                new TypeReference<LinkedHashMap<String, Object>>() { });
        if (options.scope() != null) {
            payload.put("scope", options.scope());
        }
        var body = McpJson.mapper().writeValueAsBytes(payload);
        var response = fetchOf(options.fetch())
                .fetch(new McpFetch.Request("POST", url, headers, body));
        if (!response.ok()) {
            throw new OAuthRegistrationError(response.status(),
                    new String(response.body(), StandardCharsets.UTF_8));
        }
        return OAuthMetadataParsers.parseClientInformation(parseOrNull(
                new String(response.body(), StandardCharsets.UTF_8)));
    }

    /**
     * Registration options.
     *
     * @param metadata authorization server metadata, when known
     * @param clientMetadata the document to register
     * @param scope scope to ask for in the registration
     * @param fetch HTTP fetch, defaulting to the JDK
     */
    record Options(
            @Nullable AuthorizationServerMetadata metadata,
            OAuthClientMetadata clientMetadata,
            @Nullable String scope,
            @Nullable McpFetch fetch) {
    }

    /** Exchange an authorization code for tokens (pi flow.ts:249-263). */
    static OAuthTokens exchangeAuthorizationCode(URI authorizationServerUrl, ExchangeOptions options)
            throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "authorization_code");
        params.put("code", options.code());
        params.put("code_verifier", options.codeVerifier());
        params.put("redirect_uri", options.redirectUrl());
        return tokenRequest(authorizationServerUrl, options.token(), params);
    }

    /**
     * Exchange options.
     *
     * @param token shared token request options
     * @param code the authorization code
     * @param codeVerifier the PKCE verifier of the authorization request
     * @param redirectUrl the redirect URI of the authorization request
     */
    record ExchangeOptions(
            TokenRequestOptions token,
            String code,
            String codeVerifier,
            String redirectUrl) {
    }

    /** Refresh a grant (pi flow.ts:265-275). */
    static OAuthTokens refreshAuthorization(URI authorizationServerUrl, RefreshOptions options)
            throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", options.refreshToken());
        var tokens = tokenRequest(authorizationServerUrl, options.token(), params);
        // A response without a refresh token would drop it; keep the one we used.
        var refreshToken = tokens.refreshToken() != null ? tokens.refreshToken() : options.refreshToken();
        return new OAuthTokens(tokens.accessToken(), tokens.tokenType(), tokens.expiresIn(),
                tokens.scope(), refreshToken, tokens.idToken());
    }

    /**
     * Refresh options.
     *
     * @param token shared token request options
     * @param refreshToken the refresh token to redeem
     */
    record RefreshOptions(TokenRequestOptions token, String refreshToken) {
    }

    private static @Nullable Object parseOrNull(String text) {
        try {
            return McpJson.mapper().readValue(text, Object.class);
        } catch (IOException error) {
            return null;
        }
    }
}
