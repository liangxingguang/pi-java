package com.pijava.mcp.oauth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.McpFetch;
import com.pijava.mcp.McpJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flow clauses of pi oauth.test.ts:1, 2, 7. */
class OAuthFlowTest {

    static final URI SERVER = URI.create("https://api.example.com/mcp");
    static final String PR_URL = "https://api.example.com/.well-known/oauth-protected-resource/mcp";
    static final String AS = "https://as.example";
    static final String AS_META = AS + "/.well-known/oauth-authorization-server";
    static final String REGISTER = AS + "/register";
    static final String TOKEN = AS + "/token";
    static final String AUTHORIZE = AS + "/authorize";

    static String prJson(String resource, String servers, String scopes) {
        var json = new StringBuilder("{\"resource\":\"").append(resource).append('"');
        if (servers != null) {
            json.append(",\"authorization_servers\":").append(servers);
        }
        if (scopes != null) {
            json.append(",\"scopes_supported\":").append(scopes);
        }
        return json.append('}').toString();
    }

    static String asJson(String issuer, String registrationEndpoint) {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + AUTHORIZE + "\","
                + "\"token_endpoint\":\"" + TOKEN + "\""
                + (registrationEndpoint == null ? "" : ",\"registration_endpoint\":\"" + registrationEndpoint + "\"")
                + ",\"response_types_supported\":[\"code\"],"
                + "\"token_endpoint_auth_methods_supported\":[\"none\"],"
                + "\"code_challenge_methods_supported\":[\"S256\"]}";
    }

    static AuthorizationServerMetadata metadata(String json) {
        try {
            return OAuthMetadataParsers.parseAuthorizationServerMetadata(
                    McpJson.mapper().readValue(json, Object.class));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    static OAuthFlowOptions options(McpFetch fetch) {
        return new OAuthFlowOptions(SERVER, null, null, null, null, null, fetch, false, false);
    }

    static OAuthFlowOptions exchangeOptions(McpFetch fetch, String code, String iss) {
        return new OAuthFlowOptions(SERVER, code, iss, null, null, null, fetch, false, false);
    }

    static String challengeOf(String verifier) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    // ---- Clause 1: discover, register, authorize with PKCE, then exchange ----

    @Test
    void discoversRegistersAndAuthorizesWithPkce() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .json(PR_URL, 200, prJson("https://api.example.com/mcp", "[\"https://as.example\"]",
                        "[\"org:read\"]"))
                .json(AS_META, 200, asJson(AS, REGISTER))
                .json(REGISTER, 201, "{\"client_id\":\"test-client\",\"client_secret\":\"\"}")
                .json(TOKEN, 200, "{\"access_token\":\"first-token\","
                        + "\"refresh_token\":\"refresh-token\",\"token_type\":\"Bearer\",\"scope\":\"\"}");
        var provider = new TestOAuthProvider("http://127.0.0.1/callback");

        assertThat(OAuthFlow.authorizeMcp(provider, options(fetch)))
                .isEqualTo(OAuthFlowResult.REDIRECT);
        assertThat(provider.authorizationUrl).isNotNull();
        var params = OAuthEndpoints.parseForm(provider.authorizationUrl.getRawQuery());
        assertThat(params).containsEntry("response_type", "code")
                .containsEntry("client_id", "test-client")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://127.0.0.1/callback")
                .containsEntry("state", "expected-state")
                .containsEntry("scope", "org:read")
                .containsEntry("resource", "https://api.example.com/mcp");
        // PKCE: the challenge is the S256 digest of the stored verifier.
        assertThat(params.get("code_challenge")).isEqualTo(challengeOf(provider.verifier));
        assertThat(provider.client).isNotNull();
        assertThat(provider.client.clientId()).isEqualTo("test-client");
        // An empty client_secret counts as absent, and the token endpoint is 'none'.
        assertThat(provider.client.clientSecret()).isNull();
        var registration = fetch.sentTo(REGISTER).get(0);
        assertThat(registration.body()).contains("\"redirect_uris\":[\"http://127.0.0.1/callback\"]")
                .contains("\"scope\":\"org:read\"");

        assertThat(OAuthFlow.authorizeMcp(provider,
                exchangeOptions(fetch, "test-code", AS)))
                .isEqualTo(OAuthFlowResult.AUTHORIZED);
        assertThat(provider.tokenSet.accessToken()).isEqualTo("first-token");
        assertThat(provider.tokenSet.refreshToken()).isEqualTo("refresh-token");
        // The token response named no scope, so the grant has the requested one.
        assertThat(provider.tokenSet.scope()).isEqualTo("org:read");
        var tokenBody = fetch.sentTo(TOKEN).get(0).body();
        assertThat(tokenBody).contains("grant_type=authorization_code")
                .contains("code=test-code")
                .contains("code_verifier=" + provider.verifier)
                .contains("redirect_uri=");
        // Cached discovery: the second run does not re-discover.
        assertThat(fetch.sentTo(AS_META)).hasSize(1);
    }

    // ---- Clause 1/2: refresh ----

    @Test
    void refreshesStoredTokensAndKeepsScope() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .json(TOKEN, 200, "{\"access_token\":\"a2\",\"refresh_token\":\"r2\","
                        + "\"token_type\":\"Bearer\"}");
        var provider = new TestOAuthProvider("http://127.0.0.1/callback");
        provider.discovery = new OAuthDiscoveryState(AS, metadata(asJson(AS, null)), null, null);
        provider.tokenSet = new OAuthTokens("a1", "Bearer", null, "org:read", "r1", null);
        provider.client = client();

        assertThat(OAuthFlow.authorizeMcp(provider, options(fetch)))
                .isEqualTo(OAuthFlowResult.AUTHORIZED);
        assertThat(provider.tokenSet.accessToken()).isEqualTo("a2");
        assertThat(provider.tokenSet.refreshToken()).isEqualTo("r2");
        // A refresh without a scope keeps the scope of the grant.
        assertThat(provider.tokenSet.scope()).isEqualTo("org:read");
        assertThat(fetch.sentTo(TOKEN).get(0).body()).contains("grant_type=refresh_token")
                .contains("refresh_token=r1");
        assertThat(provider.authorizationUrl).isNull();
    }

    @Test
    void refreshResponseWithoutRefreshTokenKeepsTheOneUsed() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .json(TOKEN, 200, "{\"access_token\":\"a2\",\"token_type\":\"Bearer\"}");
        var provider = new TestOAuthProvider("http://127.0.0.1/callback");
        provider.discovery = new OAuthDiscoveryState(AS, metadata(asJson(AS, null)), null, null);
        provider.tokenSet = new OAuthTokens("a1", "Bearer", null, "org:read", "r1", null);
        provider.client = client();

        assertThat(OAuthFlow.authorizeMcp(provider, options(fetch)))
                .isEqualTo(OAuthFlowResult.AUTHORIZED);
        assertThat(provider.tokenSet.refreshToken()).isEqualTo("r1");
    }

    @Test
    void invalidGrantDropsTokensAndFallsBackToRedirect() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .json(TOKEN, 400, "{\"error\":\"invalid_grant\"}");
        var provider = new TestOAuthProvider("http://127.0.0.1/callback");
        provider.discovery = new OAuthDiscoveryState(AS, metadata(asJson(AS, null)), null, null);
        provider.tokenSet = new OAuthTokens("a1", "Bearer", null, "org:read", "r1", null);
        provider.client = client();

        assertThat(OAuthFlow.authorizeMcp(provider, options(fetch)))
                .isEqualTo(OAuthFlowResult.REDIRECT);
        assertThat(provider.invalidations).containsExactly("tokens");
        assertThat(provider.authorizationUrl).isNotNull();
    }

    // ---- Clause 7: RFC 9207 iss validation ----

    @Test
    void exchangesACodeOnlyWhenIssNamesTheAuthorizationServer() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .json(TOKEN, 200, "{\"access_token\":\"token\",\"token_type\":\"Bearer\"}")
                .json(TOKEN, 200, "{\"access_token\":\"token\",\"token_type\":\"Bearer\"}");

        assertThatThrownBy(() -> OAuthFlow.authorizeMcp(providerWithIss(true),
                exchangeOptions(fetch, "other", "https://attacker.example")))
                .isInstanceOf(OAuthIssuerMismatchError.class);
        assertThatThrownBy(() -> OAuthFlow.authorizeMcp(providerWithIss(true),
                exchangeOptions(fetch, "missing", null)))
                .isInstanceOf(OAuthIssuerMismatchError.class);
        assertThat(OAuthFlow.authorizeMcp(providerWithIss(true),
                exchangeOptions(fetch, "matching", AS)))
                .isEqualTo(OAuthFlowResult.AUTHORIZED);
        // Servers that do not promise the parameter may omit it.
        assertThat(OAuthFlow.authorizeMcp(providerWithIss(false),
                exchangeOptions(fetch, "omitted", null)))
                .isEqualTo(OAuthFlowResult.AUTHORIZED);
        assertThat(fetch.sentTo(TOKEN)).hasSize(2);
        assertThat(fetch.sentTo(TOKEN).get(0).body()).contains("code=matching");
    }

    private static OAuthClientInformation client() {
        return new OAuthClientInformation("client", null, null, null, null,
                java.util.List.of(), null);
    }

    private static TestOAuthProvider providerWithIss(boolean supported) {
        var provider = new TestOAuthProvider("http://127.0.0.1/callback");
        provider.client = client();
        provider.verifier = "verifier";
        var json = "{\"issuer\":\"" + AS + "\","
                + "\"authorization_endpoint\":\"" + AUTHORIZE + "\","
                + "\"token_endpoint\":\"" + TOKEN + "\","
                + "\"response_types_supported\":[\"code\"],"
                + "\"authorization_response_iss_parameter_supported\":" + supported + "}";
        provider.discovery = new OAuthDiscoveryState(AS, metadata(json), null, null);
        return provider;
    }
}
