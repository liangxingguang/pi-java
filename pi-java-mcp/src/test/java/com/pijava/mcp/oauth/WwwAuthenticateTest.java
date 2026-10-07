package com.pijava.mcp.oauth;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Case 1: {@code parseWwwAuthenticate} (pi discovery.ts:39-56). */
class WwwAuthenticateTest {

    @Test
    void nullOrBareBearerGivesEmptyChallenge() {
        assertThat(WwwAuthenticate.parse(null)).isEqualTo(OAuthChallenge.empty());
        assertThat(WwwAuthenticate.parse("Bearer")).isEqualTo(OAuthChallenge.empty());
    }

    @Test
    void parsesQuotedBearerFields() {
        var header = "Bearer resource_metadata=\"https://x.example/.well-known/"
                + "oauth-protected-resource/mcp\", scope=\"org:read\", "
                + "error=\"invalid_token\", error_description=\"expired\"";
        var challenge = WwwAuthenticate.parse(header);
        assertThat(challenge.resourceMetadataUrl())
                .isEqualTo(URI.create("https://x.example/.well-known/"
                        + "oauth-protected-resource/mcp"));
        assertThat(challenge.scope()).isEqualTo("org:read");
        assertThat(challenge.error()).isEqualTo("invalid_token");
        assertThat(challenge.errorDescription()).isEqualTo("expired");
    }

    @Test
    void parsesBareValuesAndDpopScheme() {
        var challenge = WwwAuthenticate.parse("Bearer scope=org:read,error=invalid_token");
        assertThat(challenge.scope()).isEqualTo("org:read");
        assertThat(challenge.error()).isEqualTo("invalid_token");

        var dpop = WwwAuthenticate.parse("DPoP scope=\"x\"");
        assertThat(dpop.scope()).isEqualTo("x");
    }

    @Test
    void rejectsUnknownSchemeAndUnparseableResourceUrl() {
        assertThat(WwwAuthenticate.parse("Basic abc")).isEqualTo(OAuthChallenge.empty());
        var bad = WwwAuthenticate.parse("Bearer resource_metadata=\"not a url\", scope=\"s\"");
        assertThat(bad.resourceMetadataUrl()).isNull();
        assertThat(bad.scope()).isEqualTo("s");
    }

    @Test
    void emptyQuotedValueCountsAsAbsent() {
        // M6: an empty value carries no information.
        assertThat(WwwAuthenticate.parse("Bearer scope=\"\"").scope()).isNull();
        assertThat(WwwAuthenticate.parse("Bearer error=").error()).isNull();
    }
}
