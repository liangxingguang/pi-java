package com.pijava.mcp.runtime;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.oauth.AuthorizationServerMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code callbackSettings}（{@code oauth.ts:84-103}）与 Client ID Metadata Document
 * （{@code oauth.ts:240-260}）。
 */
class McpCallbackSettingsTest {

    private static McpOAuthSettings withCallback(String callbackUrl, Integer callbackPort) {
        return new McpOAuthSettings(null, null, callbackPort, callbackUrl, null, null, null, null);
    }

    @Test
    void defaultsToLoopbackOnAFreePort() {
        var settings = McpCallbackSettings.from(McpOAuthSettings.none());
        assertThat(settings.host()).isEqualTo("127.0.0.1");
        assertThat(settings.redirectHost()).isEqualTo("127.0.0.1");
        assertThat(settings.port()).isNull();
        assertThat(settings.path()).isEqualTo("/callback");
        assertThat(settings.fixedRedirectUrl()).isNull();
    }

    @Test
    void aConfiguredUriWithAPortIsSentExactlyAsWritten() {
        var settings = McpCallbackSettings.from(
                withCallback("http://127.0.0.1:7777/callback", 1234));
        assertThat(settings.port()).isEqualTo(7777);
        assertThat(settings.fixedRedirectUrl()).isEqualTo("http://127.0.0.1:7777/callback");
    }

    @Test
    void aConfiguredPortIsFilledIntoTheConfiguredUri() {
        var settings = McpCallbackSettings.from(withCallback("http://127.0.0.1/callback", 1234));
        assertThat(settings.port()).isEqualTo(1234);
        assertThat(settings.fixedRedirectUrl()).isEqualTo("http://127.0.0.1:1234/callback");
    }

    @Test
    void portAloneStillGivesARedirectUri() {
        var settings = McpCallbackSettings.from(withCallback(null, 1234));
        assertThat(settings.port()).isEqualTo(1234);
        assertThat(settings.fixedRedirectUrl()).isEqualTo("http://127.0.0.1:1234/callback");
    }

    @Test
    void localhostIsServedOnLoopbackButKeptInTheRedirectUri() {
        // Browsers fall back to 127.0.0.1 when ::1 refuses (oauth.ts:96).
        var settings = McpCallbackSettings.from(withCallback("http://localhost/callback", null));
        assertThat(settings.host()).isEqualTo("127.0.0.1");
        assertThat(settings.redirectHost()).isEqualTo("localhost");
    }

    @Test
    void ipv6BracketsComeOffTheHostname() {
        var settings = McpCallbackSettings.from(withCallback("http://[::1]:8080/callback", null));
        assertThat(settings.host()).isEqualTo("::1");
        assertThat(settings.redirectHost()).isEqualTo("::1");
        assertThat(settings.port()).isEqualTo(8080);
    }

    @Test
    void aHostnameIsLowercasedTheWayWhatwgUrlsAre() {
        var settings = McpCallbackSettings.from(withCallback("http://LOCALHOST/callback", null));
        assertThat(settings.host()).isEqualTo("127.0.0.1");
        assertThat(settings.redirectHost()).isEqualTo("localhost");
    }

    // ------------------------------------------------- client id metadata documents

    private static AuthorizationServerMetadata metadata(
            Boolean documentSupported, Boolean issSupported, List<String> methods) {
        return new AuthorizationServerMetadata("https://as.test",
                "https://as.test/authorize", "https://as.test/token", null, null,
                List.of("code"), null, methods, null, documentSupported, issSupported, null);
    }

    @Test
    void withoutIssTheDocumentIsSpecificToTheServer() {
        var document = McpClientMetadataDocuments.create("http://example.test/mcp",
                "http://127.0.0.1:1234/callback",
                metadata(true, false, List.of("none")));

        assertThat(document.url()).startsWith("https://pi.dev/oauth/");
        assertThat(document.url()).hasSize("https://pi.dev/oauth/".length() + 12 + "/client.json".length());
        assertThat(document.redirectUrl()).startsWith("http://127.0.0.1:1234/callback/");
    }

    @Test
    void withIssOneSharedDocumentIsEnough() {
        var document = McpClientMetadataDocuments.create("http://example.test/mcp",
                "http://127.0.0.1:1234/callback",
                metadata(true, true, List.of("none")));

        assertThat(document.url()).isEqualTo("https://pi.dev/oauth/client.json");
        assertThat(document.redirectUrl()).isEqualTo("http://127.0.0.1:1234/callback");
    }

    @Test
    void anUnsupportingServerIsRefusedWithItsOwnMessage() {
        assertThatThrownBy(() -> McpClientMetadataDocuments.create("http://example.test/mcp",
                "http://127.0.0.1:1234/callback", metadata(true, false, List.of("client_secret_post"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The authorization server does not support Client ID Metadata Documents"
                        + " for public clients; remove oauth.clientRegistration \"cimd\"");
    }

    @Test
    void noMetadataAtAllIsAlsoRefused() {
        assertThatThrownBy(() -> McpClientMetadataDocuments.create("http://example.test/mcp",
                "http://127.0.0.1:1234/callback", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCallbackIdIsTwelveUrlSafeCharacters() {
        var id = McpClientMetadataDocuments.callbackId("http://example.test/mcp");
        assertThat(id).hasSize(12).matches("[A-Za-z0-9_-]{12}");
        // The fragment is dropped before hashing (oauth.ts:230).
        assertThat(McpClientMetadataDocuments.callbackId("http://example.test/mcp#x")).isEqualTo(id);
    }
}
