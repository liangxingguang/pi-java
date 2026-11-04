package com.pijava.mcp.runtime;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.oauth.MemoryOAuthStateStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client-registration decision tree ({@code oauth.ts:246-330,504-530}).
 */
class McpOAuthSettingsTest {

    private static McpOAuthSettings settings(McpOAuthSettings.Registration registration,
                                             String clientId, String clientName, String callbackUrl) {
        return new McpOAuthSettings(clientId, null, null, callbackUrl, null, clientName,
                registration, null);
    }

    @Test
    void autoUsesTheDocumentOnlyForPiItselfWithACompatibleCallback() {
        // pi-java renames the application, so auto always falls back to dynamic registration,
        // exactly as pi does for a rebranded build (oauth.ts:246-262).
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(null, null, null, null), "pi-java")).isFalse();
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(McpOAuthSettings.Registration.AUTO, null, null, null), "pi-java")).isFalse();

        // The same rules with pi's own name.
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(null, null, null, null), "pi")).isTrue();
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(null, "client", null, null), "pi")).isFalse();
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(null, null, "My client", null), "pi")).isFalse();
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(null, null, null, "http://127.0.0.1:8080/callback"), "pi")).isTrue();
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(null, null, null, "http://localhost:8080/other"), "pi")).isFalse();
    }

    @Test
    void explicitModesOverrideTheDecision() {
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(McpOAuthSettings.Registration.CIMD, null, null, null), "pi-java")).isTrue();
        assertThat(McpOAuthSettings.offersClientMetadataDocument(
                settings(McpOAuthSettings.Registration.DCR, null, null, null), "pi-java")).isFalse();
    }

    @Test
    void autoAndCimdAttachTheMetadataDocumentDifferently() {
        var store = new MemoryOAuthStateStore();
        var auto = McpAuthProviders.createProvider("https://s.example/mcp", store,
                settings(McpOAuthSettings.Registration.AUTO, null, null, null),
                "http://127.0.0.1:9/callback", "pi-java", url -> { });
        // Auto falls back to dynamic registration, so no document is offered.
        assertThat(auto.clientMetadataDocument(null)).isNull();

        var cimd = McpAuthProviders.createProvider("https://s.example/mcp", store,
                settings(McpOAuthSettings.Registration.CIMD, null, null, null),
                "http://127.0.0.1:9/callback", "pi-java", url -> { });
        assertThatThrownBy(() -> cimd.clientMetadataDocument(null))
                .hasMessage("The authorization server does not support Client ID Metadata"
                        + " Documents for public clients; remove oauth.clientRegistration \"cimd\"");
    }

    @Test
    void registrationNamesMapToModes() {
        assertThat(McpOAuthSettings.registrationOf("auto"))
                .isEqualTo(McpOAuthSettings.Registration.AUTO);
        assertThat(McpOAuthSettings.registrationOf("dcr"))
                .isEqualTo(McpOAuthSettings.Registration.DCR);
        assertThat(McpOAuthSettings.registrationOf("cimd"))
                .isEqualTo(McpOAuthSettings.Registration.CIMD);
        assertThat(McpOAuthSettings.registrationOf(null)).isNull();
    }

    @Test
    void theCallbackCompatibilityCheck() {
        assertThat(com.pijava.mcp.config.McpServerConfigs.isClientMetadataDocumentCallback(null))
                .isTrue();
        assertThat(com.pijava.mcp.config.McpServerConfigs.isClientMetadataDocumentCallback(
                "http://127.0.0.1:8080/callback")).isTrue();
        assertThat(com.pijava.mcp.config.McpServerConfigs.isClientMetadataDocumentCallback(
                "http://localhost:8080/callback")).isTrue();
        assertThat(com.pijava.mcp.config.McpServerConfigs.isClientMetadataDocumentCallback(
                "http://[::1]:8080/callback")).isFalse();
        assertThat(com.pijava.mcp.config.McpServerConfigs.isClientMetadataDocumentCallback(
                "http://127.0.0.1:8080/other")).isFalse();
    }
}
