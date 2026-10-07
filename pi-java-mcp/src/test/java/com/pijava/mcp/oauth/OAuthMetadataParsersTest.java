package com.pijava.mcp.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.McpJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cases 2-3: metadata parsers (pi types.ts:134-176). */
class OAuthMetadataParsersTest {

    private static Map<String, Object> map(String json) {
        try {
            return McpJson.mapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Case 2: protected resource metadata ----

    @Test
    void parsesProtectedResourceWithUnknownFieldsInExtension() {
        var metadata = OAuthMetadataParsers.parseProtectedResourceMetadata(map("""
                {
                  "resource": "https://s.example/mcp",
                  "authorization_servers": ["https://as.example"],
                  "scopes_supported": ["a", "b"],
                  "name": "Server X",
                  "tls_client_certificate_access_supported": true
                }"""));
        assertThat(metadata.resource()).isEqualTo("https://s.example/mcp");
        assertThat(metadata.authorizationServers()).containsExactly("https://as.example");
        assertThat(metadata.scopesSupported()).containsExactly("a", "b");
        assertThat(metadata.extension()).containsEntry("name", "Server X");
        assertThat(metadata.extension()).containsEntry("tls_client_certificate_access_supported", true);
        assertThat(metadata.extension()).doesNotContainKeys("resource", "authorization_servers");
    }

    @Test
    void protectedResourceOptionalFieldsMayBeAbsent() {
        var metadata = OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":\"https://s.example/mcp\"}"));
        assertThat(metadata.authorizationServers()).isNull();
        assertThat(metadata.scopesSupported()).isNull();
        assertThat(metadata.extension()).isEmpty();
    }

    @Test
    void rejectsInvalidResource() {
        assertThatThrownBy(() -> OAuthMetadataParsers
                .parseProtectedResourceMetadata(new LinkedHashMap<>()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":\"\"}"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":42}"))).isInstanceOf(RuntimeException.class);
        // M4: dangerous schemes.
        for (var dangerous : List.of(
                "javascript:alert(1)", "data:text/plain,x", "vbscript:msgbox")) {
            assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                    map("{\"resource\":\"" + dangerous + "\"}")))
                    .as(dangerous).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":\"not a url\"}"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsInvalidAuthorizationServersAndScopes() {
        assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":\"https://s.example\",\"authorization_servers\":\"x\"}")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":\"https://s.example\",\"authorization_servers\":[42]}")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":\"https://s.example\",\"authorization_servers\":[\"not a url\"]}")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers.parseProtectedResourceMetadata(
                map("{\"resource\":\"https://s.example\",\"scopes_supported\":[1]}")))
                .isInstanceOf(RuntimeException.class);
    }

    // ---- Case 3: authorization server metadata ----

    @Test
    void parsesAuthorizationServerMetadata() {
        var metadata = OAuthMetadataParsers.parseAuthorizationServerMetadata(map("""
                {
                  "issuer": "https://as.example",
                  "authorization_endpoint": "https://as.example/authorize",
                  "token_endpoint": "https://as.example/token",
                  "registration_endpoint": "https://as.example/register",
                  "scopes_supported": ["openid"],
                  "response_types_supported": ["code"],
                  "grant_types_supported": ["authorization_code"],
                  "token_endpoint_auth_methods_supported": ["none"],
                  "code_challenge_methods_supported": ["S256"],
                  "client_id_metadata_document_supported": true,
                  "authorization_response_iss_parameter_supported": false,
                  "introspection_endpoint": "https://as.example/introspect"
                }"""));
        assertThat(metadata.issuer()).isEqualTo("https://as.example");
        assertThat(metadata.authorizationEndpoint()).isEqualTo("https://as.example/authorize");
        assertThat(metadata.tokenEndpoint()).isEqualTo("https://as.example/token");
        assertThat(metadata.registrationEndpoint()).isEqualTo("https://as.example/register");
        assertThat(metadata.responseTypesSupported()).containsExactly("code");
        assertThat(metadata.grantTypesSupported()).containsExactly("authorization_code");
        assertThat(metadata.tokenEndpointAuthMethodsSupported()).containsExactly("none");
        assertThat(metadata.codeChallengeMethodsSupported()).containsExactly("S256");
        assertThat(metadata.clientIdMetadataDocumentSupported()).isTrue();
        assertThat(metadata.authorizationResponseIssParameterSupported()).isFalse();
        assertThat(metadata.extension())
                .containsEntry("introspection_endpoint", "https://as.example/introspect");
    }

    @Test
    void authorizationServerAllowsMinimalShapeWithDefaults() {
        var metadata = OAuthMetadataParsers.parseAuthorizationServerMetadata(map("""
                {
                  "issuer": "https://as.example",
                  "authorization_endpoint": "https://as.example/authorize",
                  "token_endpoint": "https://as.example/token",
                  "response_types_supported": ["code"]
                }"""));
        assertThat(metadata.registrationEndpoint()).isNull();
        assertThat(metadata.grantTypesSupported()).isNull();
        assertThat(metadata.clientIdMetadataDocumentSupported()).isNull();
    }

    @Test
    void rejectsMissingIssuerEndpointsOrResponseTypes() {
        var base = "{\"issuer\":\"https://as.example\","
                + "\"authorization_endpoint\":\"https://as.example/authorize\","
                + "\"token_endpoint\":\"https://as.example/token\","
                + "\"response_types_supported\":[\"code\"]}";
        assertThatThrownBy(() -> OAuthMetadataParsers
                .parseAuthorizationServerMetadata(
                        map(base.replace("\"issuer\":\"https://as.example\",", ""))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers
                .parseAuthorizationServerMetadata(
                        map(base.replace(",\"response_types_supported\":[\"code\"]", ""))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers
                .parseAuthorizationServerMetadata(
                        map(base.replace("\"response_types_supported\":[\"code\"]",
                                "\"response_types_supported\":\"code\""))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers
                .parseAuthorizationServerMetadata(
                        map(base.replace("\"token_endpoint\":\"https://as.example/token\",", ""))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> OAuthMetadataParsers
                .parseAuthorizationServerMetadata(
                        map(base.replace("\"authorization_endpoint\":\"https://as.example/authorize\",", ""))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void emptyRegistrationEndpointAbsentAndNonBooleanDropped() {
        var metadata = OAuthMetadataParsers.parseAuthorizationServerMetadata(map("""
                {
                  "issuer": "https://as.example",
                  "authorization_endpoint": "https://as.example/authorize",
                  "token_endpoint": "https://as.example/token",
                  "response_types_supported": ["code"],
                  "registration_endpoint": "",
                  "client_id_metadata_document_supported": "yes",
                  "authorization_response_iss_parameter_supported": 1
                }"""));
        assertThat(metadata.registrationEndpoint()).isNull();
        assertThat(metadata.clientIdMetadataDocumentSupported()).isNull();
        assertThat(metadata.authorizationResponseIssParameterSupported()).isNull();
    }
}
