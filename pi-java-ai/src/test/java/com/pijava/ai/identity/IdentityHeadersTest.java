package com.pijava.ai.identity;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 B266 / pi {@code ai/test/identity.test.ts:68-156} 的 Java 对位。
 */
class IdentityHeadersTest {

    private static final RuntimeInfo RUNTIME = new RuntimeInfo("win32", "java/25", "x64");

    private static ClientIdentity identity(UserAgentMode mode, AttributionSetting attribution,
                                           String version) {
        return new ClientIdentity(mode, attribution, version);
    }

    @Test
    void formatsUserAgents() {
        assertThat(IdentityHeaders.formatUserAgent(null, RUNTIME))
            .isEqualTo("pi-java/" + PiAiVersion.VERSION + " (win32; java/25; x64)");
        assertThat(IdentityHeaders.formatUserAgent(
            identity(null, AttributionSetting.UNSET, "9.9.9"), RUNTIME))
            .isEqualTo("pi-java/9.9.9 (win32; java/25; x64)");
        assertThat(IdentityHeaders.formatUserAgent(
            identity(UserAgentMode.SHORT, AttributionSetting.UNSET, "9.9.9"), RUNTIME))
            .isEqualTo("pi");
        assertThat(IdentityHeaders.formatUserAgent(
            identity(new UserAgentMode.Custom("acme/1.0"), AttributionSetting.UNSET, null),
            RUNTIME))
            .isEqualTo("acme/1.0");
    }

    @Test
    void sendsNoAttributionUnlessEnabled() {
        var openRouter = new ClientIdentityTarget("openrouter", "https://openrouter.ai/api/v1");
        assertThat(IdentityHeaders.attributionHeaders(openRouter, null)).isEmpty();
        assertThat(IdentityHeaders.attributionHeaders(openRouter,
            identity(null, AttributionSetting.OFF, null))).isEmpty();
        assertThat(IdentityHeaders.identityHeaders(openRouter, null))
            .containsOnlyKeys("User-Agent")
            .containsEntry("User-Agent", "pi-java/" + PiAiVersion.VERSION
                + " (" + RuntimeInfo.current().platform() + "; "
                + RuntimeInfo.current().runtime() + "; " + RuntimeInfo.current().arch() + ")");
    }

    @Test
    void attributesOpenRouterRequests() {
        var openRouter = new ClientIdentityTarget("openrouter", "https://openrouter.ai/api/v1");
        var pi = identity(null, AttributionSetting.of(ClientAttribution.PI), null);
        assertThat(IdentityHeaders.attributionHeaders(openRouter, pi)).containsExactlyInAnyOrderEntriesOf(
            Map.of("HTTP-Referer", "https://pi.dev",
                "X-OpenRouter-Title", "pi",
                "X-OpenRouter-Categories", "cli-agent"));

        var acme = identity(null, AttributionSetting.of(
            new ClientAttribution("acme", "https://acme.example",
                List.of("cli-agent", "cloud-agent"))), null);
        assertThat(IdentityHeaders.attributionHeaders(openRouter, acme))
            .containsEntry("X-OpenRouter-Categories", "cli-agent,cloud-agent");

        var noCategories = identity(null, AttributionSetting.of(
            new ClientAttribution("acme", "https://acme.example", List.of())), null);
        assertThat(IdentityHeaders.attributionHeaders(openRouter, noCategories))
            .doesNotContainKey("X-OpenRouter-Categories");

        var custom = new ClientIdentityTarget("custom", "not-a-url-openrouter.ai");
        assertThat(IdentityHeaders.attributionHeaders(custom, pi))
            .containsEntry("X-OpenRouter-Title", "pi");
    }

    @Test
    void attributesNvidiaAndOpenCode() {
        var pi = identity(null, AttributionSetting.of(ClientAttribution.PI), null);
        assertThat(IdentityHeaders.attributionHeaders(
            new ClientIdentityTarget("nvidia", "https://example.test/v1"), pi))
            .containsExactlyEntriesOf(Map.of("X-BILLING-INVOKE-ORIGIN", "Pi"));
        assertThat(IdentityHeaders.attributionHeaders(
            new ClientIdentityTarget("custom-nim", "https://integrate.api.nvidia.com/v1"),
            identity(null, AttributionSetting.of(
                new ClientAttribution("acme", "https://acme.example")), null)))
            .containsExactlyEntriesOf(Map.of("X-BILLING-INVOKE-ORIGIN", "Acme"));
        assertThat(IdentityHeaders.attributionHeaders(
            new ClientIdentityTarget("opencode", "https://opencode.ai/zen/v1"), pi))
            .containsExactlyEntriesOf(Map.of("x-opencode-client", "pi"));
        assertThat(IdentityHeaders.attributionHeaders(
            new ClientIdentityTarget("custom", "https://opencode.ai/zen/v1"),
            identity(null, AttributionSetting.of(
                new ClientAttribution("acme", "https://acme.example")), null)))
            .containsExactlyEntriesOf(Map.of("x-opencode-client", "acme"));
    }

    @Test
    void doesNotAttributeGatewaysToNvidia() {
        var pi = identity(null, AttributionSetting.of(ClientAttribution.PI), null);
        assertThat(IdentityHeaders.attributionHeaders(
            new ClientIdentityTarget("vercel-ai-gateway", "https://ai-gateway.vercel.sh/v1"), pi))
            .isEmpty();
        assertThat(IdentityHeaders.attributionHeaders(
            new ClientIdentityTarget("openrouter", "https://openrouter.ai/api/v1"), pi))
            .doesNotContainKey("X-BILLING-INVOKE-ORIGIN");
    }

    @Test
    void openCodeSessionHeaderOnlyForOpenCode() {
        var openCode = new ClientIdentityTarget("custom", "https://opencode.ai/zen/v1");
        assertThat(IdentityHeaders.openCodeSessionHeaders(openCode, "session-1"))
            .containsExactlyEntriesOf(Map.of("x-opencode-session", "session-1"));
        assertThat(IdentityHeaders.openCodeSessionHeaders(openCode, null)).isEmpty();
        assertThat(IdentityHeaders.openCodeSessionHeaders(openCode, "")).isEmpty();
        assertThat(IdentityHeaders.openCodeSessionHeaders(
            new ClientIdentityTarget("openai", "https://api.openai.com/v1"), "session-1")).isEmpty();
    }

    @Test
    void mergeHeadersIsCaseInsensitiveAndNullDeletes() {
        var base = Map.of("User-Agent", "identity", "X-Keep", "keep");
        var override = new java.util.LinkedHashMap<String, Object>();
        override.put("user-agent", "override");
        override.put("X-Drop", null);
        var merged = IdentityHeaders.mergeHeaders(base, override);
        assertThat(merged).containsEntry("user-agent", "override")
            .containsEntry("X-Keep", "keep")
            .doesNotContainKey("User-Agent")
            .doesNotContainKey("X-Drop");
    }
}
