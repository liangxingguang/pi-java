package com.pijava.mcp.oauth;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure endpoint and authorization-request helpers (pi flow.ts:102-184, 277-290). */
class OAuthEndpointsTest {

    private static OAuthClientInformation client(String id, String secret) {
        return new OAuthClientInformation(id, secret, null, null, null, List.of(), null);
    }

    @Test
    void selectsClientAuthMethodInOrder() {
        // A hinted method wins when it is supported.
        var hinted = new OAuthClientInformation("id", "secret", null, null,
                "client_secret_post", List.of(), null);
        assertThat(OAuthEndpoints.selectClientAuthMethod(hinted, List.of("client_secret_post")))
                .isEqualTo("client_secret_post");
        // A hinted method the server does not list falls through.
        assertThat(OAuthEndpoints.selectClientAuthMethod(hinted, List.of("none")))
                .isEqualTo("none");
        // No hint: an empty supported list means basic with a secret, none without.
        assertThat(OAuthEndpoints.selectClientAuthMethod(client("id", "secret"), List.of()))
                .isEqualTo("client_secret_basic");
        assertThat(OAuthEndpoints.selectClientAuthMethod(client("id", null), List.of()))
                .isEqualTo("none");
        // Basic is preferred over post when both are offered.
        assertThat(OAuthEndpoints.selectClientAuthMethod(client("id", "secret"),
                List.of("client_secret_post", "client_secret_basic")))
                .isEqualTo("client_secret_basic");
        assertThat(OAuthEndpoints.selectClientAuthMethod(client("id", "secret"),
                List.of("client_secret_post")))
                .isEqualTo("client_secret_post");
        assertThat(OAuthEndpoints.selectClientAuthMethod(client("id", null), List.of("none")))
                .isEqualTo("none");
        // A secret with no applicable method still posts it.
        assertThat(OAuthEndpoints.selectClientAuthMethod(client("id", "secret"),
                List.of("private_key_jwt")))
                .isEqualTo("client_secret_post");
    }

    @Test
    void appliesClientAuthentication() {
        var headers = new java.util.LinkedHashMap<String, String>();
        var params = new java.util.LinkedHashMap<String, String>();
        OAuthEndpoints.applyClientAuthentication("client_secret_basic", client("id", "secret"),
                headers, params);
        assertThat(headers.get("Authorization"))
                .isEqualTo("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("id:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(params).isEmpty();

        headers.clear();
        OAuthEndpoints.applyClientAuthentication("client_secret_post", client("id", "secret"),
                headers, params);
        assertThat(headers).isEmpty();
        assertThat(params).containsEntry("client_id", "id").containsEntry("client_secret", "secret");

        params.clear();
        OAuthEndpoints.applyClientAuthentication("none", client("id", "secret"), headers, params);
        assertThat(params).containsEntry("client_id", "id").doesNotContainKey("client_secret");

        assertThatThrownBy(() -> OAuthEndpoints.applyClientAuthentication("client_secret_basic",
                client("id", null), headers, params))
                .hasMessage("client_secret_basic requires a client secret");
    }

    @Test
    void stepUpScopeKeepsGrantedScopesAndDeduplicates() {
        assertThat(OAuthEndpoints.stepUpScope("repo read:org", "admin"))
                .isEqualTo("repo read:org admin");
        // Duplicates across both sources collapse, order is preserved.
        assertThat(OAuthEndpoints.stepUpScope("repo read", "read admin"))
                .isEqualTo("repo read admin");
        assertThat(OAuthEndpoints.stepUpScope(null, "admin")).isEqualTo("admin");
        assertThat(OAuthEndpoints.stepUpScope("repo", null)).isNull();
        assertThat(OAuthEndpoints.stepUpScope("repo", "")).isEqualTo("repo");
    }

    @Test
    void withScopeTreatsEmptyScopeAsAbsent() {
        var tokens = new OAuthTokens("a", "Bearer", null, null, "r", null);
        assertThat(OAuthEndpoints.withScope(tokens, "org:read").scope()).isEqualTo("org:read");
        assertThat(OAuthEndpoints.withScope(tokens, "").scope()).isNull();
        assertThat(OAuthEndpoints.withScope(tokens, null).scope()).isNull();
        // An explicit scope in the response wins.
        var scoped = new OAuthTokens("a", "Bearer", null, "given", "r", null);
        assertThat(OAuthEndpoints.withScope(scoped, "org:read").scope()).isEqualTo("given");
    }

    @Test
    void secureEndpointRequiresHttpsOutsideLoopback() {
        assertThat(OAuthEndpoints.secureEndpoint(URI.create("https://as.example/token")))
                .hasToString("https://as.example/token");
        assertThat(OAuthEndpoints.secureEndpoint(URI.create("http://127.0.0.1:9/token")))
                .hasToString("http://127.0.0.1:9/token");
        assertThat(OAuthEndpoints.secureEndpoint(URI.create("http://localhost:9/token")))
                .hasToString("http://localhost:9/token");
        assertThatThrownBy(() ->
                OAuthEndpoints.secureEndpoint(URI.create("http://as.example/token")))
                .isInstanceOf(OAuthInsecureEndpointError.class)
                .hasMessage("Refusing to send OAuth credentials to non-HTTPS endpoint "
                        + "http://as.example/token");
    }

    @Test
    void startAuthorizationAddsPromptForOfflineAccess() {
        var metadata = OAuthFlowTest.metadata("{\"issuer\":\"https://as.example\","
                + "\"authorization_endpoint\":\"https://as.example/authorize\","
                + "\"token_endpoint\":\"https://as.example/token\","
                + "\"response_types_supported\":[\"code\"]}");
        var request = OAuthEndpoints.startAuthorization(URI.create("https://as.example"), metadata,
                client("id", null), "http://127.0.0.1/callback", "read offline_access", "s", null);
        var params = OAuthEndpoints.parseForm(request.authorizationUrl().getRawQuery());
        assertThat(params).containsEntry("prompt", "consent")
                .containsEntry("scope", "read offline_access")
                .containsEntry("state", "s");
        assertThat(request.authorizationUrl().toString()).startsWith("https://as.example/authorize?");
    }

    @Test
    void startAuthorizationRejectsUnsupportedCodeOrPkce() {
        var noCode = OAuthFlowTest.metadata("{\"issuer\":\"https://as.example\","
                + "\"authorization_endpoint\":\"https://as.example/authorize\","
                + "\"token_endpoint\":\"https://as.example/token\","
                + "\"response_types_supported\":[\"token\"]}");
        assertThatThrownBy(() -> OAuthEndpoints.startAuthorization(URI.create("https://as.example"),
                noCode, client("id", null), "http://127.0.0.1/callback", null, null, null))
                .hasMessage("Authorization server does not support authorization codes");

        var noS256 = OAuthFlowTest.metadata("{\"issuer\":\"https://as.example\","
                + "\"authorization_endpoint\":\"https://as.example/authorize\","
                + "\"token_endpoint\":\"https://as.example/token\","
                + "\"response_types_supported\":[\"code\"],"
                + "\"code_challenge_methods_supported\":[\"plain\"]}");
        assertThatThrownBy(() -> OAuthEndpoints.startAuthorization(URI.create("https://as.example"),
                noS256, client("id", null), "http://127.0.0.1/callback", null, null, null))
                .hasMessage("Authorization server does not support PKCE S256");
    }

    @Test
    void registerClientRequiresAnEndpointWhenMetadataIsKnown() {
        var metadata = OAuthFlowTest.metadata("{\"issuer\":\"https://as.example\","
                + "\"authorization_endpoint\":\"https://as.example/authorize\","
                + "\"token_endpoint\":\"https://as.example/token\","
                + "\"response_types_supported\":[\"code\"]}");
        var fetch = new ScriptedMcpFetch();
        assertThatThrownBy(() -> OAuthTokenRequests.registerClient(URI.create("https://as.example"),
                new OAuthTokenRequests.Options(metadata,
                        new OAuthClientMetadata(List.of("http://127.0.0.1/cb"), null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null, null),
                        null, fetch)))
                .hasMessage("Authorization server does not support dynamic client registration");
        assertThat(fetch.sent()).isEmpty();
    }

    @Test
    void tokenRequestChecksAnErrorBodyBeforeTheStatus() throws Exception {
        // A non-2xx status carrying an OAuth error must surface the OAuth error code,
        // not a generic server_error.
        var fetch = new ScriptedMcpFetch()
                .json("https://as.example/token", 400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"expired\"}");
        assertThatThrownBy(() -> OAuthTokenRequests.exchangeAuthorizationCode(
                URI.create("https://as.example"),
                new OAuthTokenRequests.ExchangeOptions(
                        new OAuthTokenRequests.TokenRequestOptions(null, client("id", null),
                                null, null, fetch),
                        "code", "verifier", "http://127.0.0.1/callback")))
                .isInstanceOf(OAuthError.class)
                .satisfies(error -> {
                    var oauth = (OAuthError) error;
                    assertThat(oauth.code()).isEqualTo("invalid_grant");
                    assertThat(oauth.getMessage()).isEqualTo("expired");
                });
    }
}
