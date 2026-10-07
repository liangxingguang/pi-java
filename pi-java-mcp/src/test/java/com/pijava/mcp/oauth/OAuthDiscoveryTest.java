package com.pijava.mcp.oauth;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cases 4-8: discovery flow (pi discovery.ts:64-185). */
class OAuthDiscoveryTest {

    private static final String SERVER = "https://api.example.com/mcp";
    private static final String PR_SUFFIXED =
            "https://api.example.com/.well-known/oauth-protected-resource/mcp";
    private static final String PR_ROOT =
            "https://api.example.com/.well-known/oauth-protected-resource";

    private static String prJson(String resource, String servers) {
        return "{\"resource\":\"" + resource + "\""
                + (servers == null ? "" : ",\"authorization_servers\":" + servers) + "}";
    }

    private static final String AS_OAUTH =
            "https://as.example/.well-known/oauth-authorization-server";
    private static final String AS_OIDC =
            "https://as.example/.well-known/openid-configuration";

    private static String asJson(String issuer) {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"https://as.example/authorize\","
                + "\"token_endpoint\":\"https://as.example/token\","
                + "\"response_types_supported\":[\"code\"]}";
    }

    // ---- Case 4: candidate URL ordering ----

    @Test
    void buildsRootCandidatesForBareOrigin() {
        var candidates = OAuthDiscovery
                .buildAuthorizationServerDiscoveryUrls(URI.create("https://as.example"));
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0))
                .isEqualTo(new OAuthDiscovery.Candidate(URI.create(AS_OAUTH), "oauth"));
        assertThat(candidates.get(1))
                .isEqualTo(new OAuthDiscovery.Candidate(URI.create(AS_OIDC), "oidc"));
    }

    @Test
    void buildsThreeCandidatesWithPathSuffix() {
        var candidates = OAuthDiscovery
                .buildAuthorizationServerDiscoveryUrls(URI.create("https://as.example/foo/"));
        assertThat(candidates).hasSize(3);
        assertThat(candidates.get(0).url()).hasToString(
                "https://as.example/.well-known/oauth-authorization-server/foo");
        assertThat(candidates.get(1).url()).hasToString(
                "https://as.example/.well-known/openid-configuration/foo");
        assertThat(candidates.get(2)).isEqualTo(new OAuthDiscovery.Candidate(
                URI.create("https://as.example/foo/.well-known/openid-configuration"), "oidc"));
    }

    // ---- Case 5: protected resource discovery with fallback ----

    @Test
    void fallsBackFromSuffixedMissToRoot() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .status(PR_SUFFIXED, 404)
                .json(PR_ROOT, 200, prJson("https://api.example.com/mcp", null));
        var metadata = OAuthDiscovery.discoverProtectedResourceMetadata(
                URI.create(SERVER), null, "2025-06-18", fetch);
        assertThat(metadata.resource()).isEqualTo("https://api.example.com/mcp");
        assertThat(fetch.requested()).extracting(URI::toString)
                .containsExactly(PR_SUFFIXED, PR_ROOT);
        assertThat(fetch.lastHeaders())
                .containsEntry("Accept", "application/json")
                .containsEntry("MCP-Protocol-Version", "2025-06-18");
    }

    @Test
    void badGatewayAlsoMissesButServerErrorDoesNot() throws Exception {
        var fallback = new ScriptedMcpFetch()
                .status(PR_SUFFIXED, 502)
                .json(PR_ROOT, 200, prJson("https://api.example.com/mcp", null));
        assertThat(OAuthDiscovery.discoverProtectedResourceMetadata(
                URI.create(SERVER), null, null, fallback).resource())
                .isEqualTo("https://api.example.com/mcp");

        var noFallback = new ScriptedMcpFetch().status(PR_SUFFIXED, 500);
        assertThatThrownBy(() -> OAuthDiscovery.discoverProtectedResourceMetadata(
                URI.create(SERVER), null, null, noFallback))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("HTTP 500 loading OAuth protected resource metadata");
    }

    @Test
    void explicitResourceUrlHasNoFallback() throws Exception {
        var explicit = "https://cdn.example.com/pr.json";
        var fetch = new ScriptedMcpFetch().status(explicit, 404);
        assertThatThrownBy(() -> OAuthDiscovery.discoverProtectedResourceMetadata(
                URI.create(SERVER), URI.create(explicit), null, fetch))
                .hasMessage("HTTP 404 loading OAuth protected resource metadata");
        assertThat(fetch.requested()).hasSize(1);
    }

    // ---- Case 6: authorization server discovery ----

    @Test
    void oauthMissFallsThroughToOidcCandidate() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .status(AS_OAUTH, 404)
                .json(AS_OIDC, 200, asJson("https://as.example"));
        var metadata = OAuthDiscovery.discoverAuthorizationServerMetadata(
                URI.create("https://as.example"), fetch, "2025-06-18", false);
        assertThat(metadata).isNotNull();
        assertThat(metadata.issuer()).isEqualTo("https://as.example");
        assertThat(fetch.requested()).extracting(URI::toString)
                .containsExactly(AS_OAUTH, AS_OIDC);
        assertThat(fetch.lastHeaders())
                .containsEntry("MCP-Protocol-Version", "2025-06-18");
    }

    @Test
    void rejectsIssuerMismatchAndAcceptsTrailingSlashAlignment() throws Exception {
        var mismatch = new ScriptedMcpFetch()
                .json(AS_OAUTH, 200, asJson("https://attacker.example"));
        assertThatThrownBy(() -> OAuthDiscovery.discoverAuthorizationServerMetadata(
                URI.create("https://as.example"), mismatch, null, false))
                .isInstanceOf(OAuthIssuerMismatchError.class)
                .satisfies(error -> {
                    var issuerError = (OAuthIssuerMismatchError) error;
                    assertThat(issuerError.expected()).isEqualTo("https://as.example");
                    assertThat(issuerError.received()).isEqualTo("https://attacker.example");
                });

        // Bare-origin URL gains a trailing slash; compare without one on either side.
        var aligned = new ScriptedMcpFetch()
                .json("https://as.example/.well-known/oauth-authorization-server", 200,
                        asJson("https://as.example"));
        assertThat(OAuthDiscovery.discoverAuthorizationServerMetadata(
                URI.create("https://as.example/"), aligned, null, false)).isNotNull();
    }

    @Test
    void allCandidatesMissReturnsNullAndOtherErrorsThrow() throws Exception {
        var allMiss = new ScriptedMcpFetch()
                .status(AS_OAUTH, 404)
                .status(AS_OIDC, 403);
        assertThat(OAuthDiscovery.discoverAuthorizationServerMetadata(
                URI.create("https://as.example"), allMiss, null, false)).isNull();

        var broken = new ScriptedMcpFetch().status(AS_OAUTH, 500);
        assertThatThrownBy(() -> OAuthDiscovery.discoverAuthorizationServerMetadata(
                URI.create("https://as.example"), broken, null, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 500 loading authorization server metadata");
    }

    @Test
    void skipIssuerValidationAcceptsMismatchedIssuer() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .json(AS_OAUTH, 200, asJson("https://attacker.example"));
        assertThat(OAuthDiscovery.discoverAuthorizationServerMetadata(
                URI.create("https://as.example"), fetch, null, true)).isNotNull();
    }

    // ---- Case 7: server info ----

    @Test
    void serverInfoUsesFirstAuthorizationServerFromResourceMetadata() throws Exception {
        var fetch = new ScriptedMcpFetch()
                .json(PR_SUFFIXED, 200,
                        prJson("https://api.example.com/mcp", "[\"https://as.example\"]"))
                .json(AS_OAUTH, 200, asJson("https://as.example"));
        var info = OAuthDiscovery.discoverOAuthServerInfo(
                URI.create(SERVER), fetch, new OAuthDiscovery.Options(null, null, false));
        assertThat(info.authorizationServerUrl()).isEqualTo("https://as.example");
        assertThat(info.authorizationServerMetadata()).isNotNull();
        assertThat(info.resourceMetadata()).isNotNull();
    }

    @Test
    void invalidResourceMetadataFallsBackToServerOrigin() throws Exception {
        var originOauth = "https://api.example.com/.well-known/oauth-authorization-server";
        var fetch = new ScriptedMcpFetch()
                .json(PR_SUFFIXED, 200,
                        prJson("https://api.example.com/mcp", "[\"not a url\"]"))
                .json(originOauth, 200, asJson("https://api.example.com"));
        var info = OAuthDiscovery.discoverOAuthServerInfo(
                URI.create(SERVER), fetch, new OAuthDiscovery.Options(null, null, false));
        assertThat(info.resourceMetadata()).isNull();
        assertThat(info.authorizationServerUrl()).isEqualTo("https://api.example.com/");
        assertThat(info.authorizationServerMetadata()).isNotNull();
    }

    @Test
    void networkFailureDuringProtectedResourceDiscoveryRethrows() throws Exception {
        var fetch = new ScriptedMcpFetch().networkFails(PR_SUFFIXED);
        assertThatThrownBy(() -> OAuthDiscovery.discoverOAuthServerInfo(
                URI.create(SERVER), fetch, new OAuthDiscovery.Options(null, null, false)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void configuredAuthorizationServerMetadataIsTrusted() throws Exception {
        var metadataUrl = "https://api.example.com/idp/metadata.json";
        var fetch = new ScriptedMcpFetch()
                .json(PR_SUFFIXED, 200,
                        prJson("https://api.example.com/mcp", "[\"https://api.example.com\"]"))
                .json(metadataUrl, 200, asJson("https://idp.example"));
        var info = OAuthDiscovery.discoverOAuthServerInfo(URI.create(SERVER), fetch,
                new OAuthDiscovery.Options(null, URI.create(metadataUrl), false));
        assertThat(info.authorizationServerUrl()).isEqualTo("https://idp.example");
        assertThat(info.authorizationServerMetadata().issuer())
                .isEqualTo("https://idp.example");
        assertThat(info.resourceMetadata()).isNotNull();
        assertThat(fetch.requested()).extracting(URI::toString)
                .containsExactly(PR_SUFFIXED, metadataUrl);
    }

    // ---- Case 8: resource selection ----

    @Test
    void selectsResourceByOriginAndPathPrefix() {
        var root = new OAuthProtectedResourceMetadata(
                "https://api.example.com/", null, null, null);
        assertThat(OAuthDiscovery.selectResource(URI.create("https://api.example.com/mcp"), root))
                .isEqualTo("https://api.example.com/");

        var nested = new OAuthProtectedResourceMetadata(
                "https://api.example.com/api/", null, null, null);
        assertThat(OAuthDiscovery.selectResource(
                URI.create("https://api.example.com/api/mcp"), nested))
                .isEqualTo("https://api.example.com/api/");

        // Hash is stripped before comparison; query preserved.
        assertThat(OAuthDiscovery.resourceUrlFromServerUrl(
                URI.create("https://api.example.com/mcp?q=1#frag")).getFragment()).isNull();
        assertThat(OAuthDiscovery.resourceUrlFromServerUrl(
                URI.create("https://api.example.com/mcp?q=1")).getQuery()).isEqualTo("q=1");
    }

    @Test
    void rejectsOriginOrPathMismatchAndNullMetadata() {
        var root = new OAuthProtectedResourceMetadata(
                "https://other.example/", null, null, null);
        assertThatThrownBy(() -> OAuthDiscovery.selectResource(
                URI.create("https://api.example.com/mcp"), root))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("does not match MCP server");

        var v1 = new OAuthProtectedResourceMetadata(
                "https://api.example.com/v1/", null, null, null);
        assertThatThrownBy(() -> OAuthDiscovery.selectResource(
                URI.create("https://api.example.com/v2/x"), v1))
                .isInstanceOf(RuntimeException.class);

        assertThat(OAuthDiscovery.selectResource(
                URI.create("https://api.example.com/mcp"), null)).isNull();
    }
}
