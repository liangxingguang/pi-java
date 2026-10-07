package com.pijava.mcp.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Endpoint and authorization-request helpers (pi flow.ts:102-184, 277-290).
 */
public final class OAuthEndpoints {

    /** Client authentication methods the flow knows how to apply. */
    static final List<String> AUTH_METHODS =
            List.of("client_secret_basic", "client_secret_post", "none");

    private OAuthEndpoints() {
    }

    static boolean loopback(@Nullable String hostname) {
        return "localhost".equals(hostname) || "127.0.0.1".equals(hostname)
                || "[::1]".equals(hostname) || "::1".equals(hostname);
    }

    /** Reject credentials to a non-HTTPS endpoint outside loopback. */
    static URI secureEndpoint(URI value) {
        if (!"https".equalsIgnoreCase(value.getScheme()) && !loopback(value.getHost())) {
            throw new OAuthInsecureEndpointError(value.toString());
        }
        return value;
    }

    /** Pick how to authenticate at the token endpoint (pi flow.ts:112-126). */
    static String selectClientAuthMethod(OAuthClientInformation information, List<String> supported) {
        var hinted = information.tokenEndpointAuthMethod();
        if (hinted != null && AUTH_METHODS.contains(hinted)
                && (supported.isEmpty() || supported.contains(hinted))) {
            return hinted;
        }
        var secret = information.clientSecret() != null;
        if (supported.isEmpty()) {
            return secret ? "client_secret_basic" : "none";
        }
        if (secret && supported.contains("client_secret_basic")) {
            return "client_secret_basic";
        }
        if (secret && supported.contains("client_secret_post")) {
            return "client_secret_post";
        }
        if (supported.contains("none")) {
            return "none";
        }
        return secret ? "client_secret_post" : "none";
    }

    /** Apply one client authentication method (pi flow.ts:128-145). */
    static void applyClientAuthentication(String method, OAuthClientInformation information,
                                          Map<String, String> headers, Map<String, String> params) {
        if ("client_secret_basic".equals(method)) {
            if (information.clientSecret() == null) {
                throw new IllegalStateException("client_secret_basic requires a client secret");
            }
            var credentials = information.clientId() + ":" + information.clientSecret();
            headers.put("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        params.put("client_id", information.clientId());
        if ("client_secret_post".equals(method) && information.clientSecret() != null) {
            params.put("client_secret", information.clientSecret());
        }
    }

    /**
     * A finished authorization request.
     *
     * @param authorizationUrl where to send the user
     * @param codeVerifier the PKCE verifier to store
     */
    record AuthorizationRequest(URI authorizationUrl, String codeVerifier) {
    }

    /** Build the authorization URL (pi flow.ts:154-184). */
    static AuthorizationRequest startAuthorization(
            URI authorizationServerUrl,
            @Nullable AuthorizationServerMetadata metadata,
            OAuthClientInformation clientInformation,
            String redirectUrl,
            @Nullable String scope,
            @Nullable String state,
            @Nullable String resource) {
        if (metadata != null && !metadata.responseTypesSupported().contains("code")) {
            throw new IllegalStateException("Authorization server does not support authorization codes");
        }
        if (metadata != null && metadata.codeChallengeMethodsSupported() != null
                && !metadata.codeChallengeMethodsSupported().contains("S256")) {
            throw new IllegalStateException("Authorization server does not support PKCE S256");
        }
        var base = metadata != null && metadata.authorizationEndpoint() != null
                ? URI.create(metadata.authorizationEndpoint())
                : authorizationServerUrl.resolve("/authorize");
        var pkce = OAuthPkce.generate();
        var params = new LinkedHashMap<String, String>();
        params.put("response_type", "code");
        params.put("client_id", clientInformation.clientId());
        params.put("code_challenge", pkce.challenge());
        params.put("code_challenge_method", "S256");
        params.put("redirect_uri", redirectUrl);
        if (state != null) {
            params.put("state", state);
        }
        if (scope != null) {
            params.put("scope", scope);
        }
        if (scope != null && containsScope(scope, "offline_access")) {
            params.put("prompt", "consent");
        }
        if (resource != null) {
            params.put("resource", resource);
        }
        return new AuthorizationRequest(toQuery(base, params), pkce.verifier());
    }

    private static boolean containsScope(String scope, String wanted) {
        for (var part : scope.split("\\s+")) {
            if (part.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record the requested scope when the response omitted one (RFC 6749 §5.1).
     * An empty scope counts as absent, matching pi's truthiness test.
     */
    static OAuthTokens withScope(OAuthTokens tokens, @Nullable String scope) {
        if (tokens.scope() != null || scope == null || scope.isEmpty()) {
            return tokens;
        }
        return new OAuthTokens(tokens.accessToken(), tokens.tokenType(), tokens.expiresIn(),
                scope, tokens.refreshToken(), tokens.idToken());
    }

    /**
     * Scopes for a step-up authorization: the challenged scopes plus the ones
     * granted so far, since a challenge may list only the missing scopes
     * (SEP-2350). Null when the challenge named no scopes.
     *
     * <p>pi exports this from {@code @earendil-works/pi-mcp/oauth} and the
     * coding-agent's sign-in orchestration uses it, so it is public here too.</p>
     *
     * @param granted scopes already granted
     * @param challenged scopes the server asked for
     * @return the merged scope string, or {@code null}
     */
    public static @Nullable String stepUpScope(@Nullable String granted, @Nullable String challenged) {
        if (challenged == null) {
            return null;
        }
        var scopes = new LinkedHashSet<String>();
        for (var source : new String[] {granted, challenged}) {
            if (source == null) {
                continue;
            }
            for (var part : source.split("\\s+")) {
                if (!part.isEmpty()) {
                    scopes.add(part);
                }
            }
        }
        return String.join(" ", scopes);
    }

    /** Append form-encoded query parameters to a URL. */
    static URI toQuery(URI base, Map<String, String> params) {
        var query = formEncode(params);
        var text = base.toString();
        if (text.contains("#")) {
            text = text.substring(0, text.indexOf('#'));
        }
        return URI.create(text + (text.contains("?") ? "&" : "?") + query);
    }

    /** application/x-www-form-urlencoded body for the given parameters. */
    static String formEncode(Map<String, String> params) {
        var builder = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    /** Parse an application/x-www-form-urlencoded query string. */
    static Map<String, String> parseForm(@Nullable String query) {
        var result = new LinkedHashMap<String, String>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (var pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            var separator = pair.indexOf('=');
            var key = separator < 0 ? pair : pair.substring(0, separator);
            var value = separator < 0 ? "" : pair.substring(separator + 1);
            result.put(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }
}
