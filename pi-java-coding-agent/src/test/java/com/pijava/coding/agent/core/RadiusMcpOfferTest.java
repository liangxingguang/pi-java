package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RadiusMcpOfferTest {

    @TempDir
    Path tmp;

    @Test
    void existingRadiusHttpKeepsFieldsAndReplacesOauthWithProviderAuth() {
        var old = new McpServerConfig.Http("http", McpExposure.DIRECT, "desc",
            Map.of("tool", McpExposure.HIDDEN), false, 30, "https://radius.pi.dev/mcp/",
            Map.of("X-Test", "yes"), new McpServerConfig.OAuth(
                "client", "secret", 1234, null, null, null, null, null), null);

        var decision = RadiusMcpOffer.decisionFor("radius", old, List.of(
            new McpServerEntry("custom-radius", old, tmp.resolve("mcp.json"), McpScope.GLOBAL, null)));
        var updated = decision.config();

        assertThat(decision.name()).isEqualTo("custom-radius");
        assertThat(decision.alreadyConfigured()).isFalse();
        assertThat(updated.auth()).isEqualTo(new McpServerConfig.Auth("radius"));
        assertThat(updated.oauth()).isNull();
        assertThat(updated.headers()).containsEntry("X-Test", "yes");
        assertThat(updated.description()).isEqualTo("desc");
        assertThat(updated.toolExposure()).containsEntry("tool", McpExposure.HIDDEN);
        assertThat(updated.exposure()).isEqualTo(McpExposure.DIRECT);
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.timeout()).isEqualTo(30);
    }

    @Test
    void missingServerUsesRadiusThenRadiusMcpOnNameCollision() {
        var collision = new McpServerEntry("radius", http("https://example.com", null),
            tmp.resolve("mcp.json"), McpScope.GLOBAL, null);

        var decision = RadiusMcpOffer.decisionFor("radius", null, List.of(collision));

        assertThat(decision.name()).isEqualTo("radius-mcp");
        assertThat(decision.config().url()).isEqualTo("https://radius.pi.dev/mcp");
        assertThat(decision.config().auth()).isEqualTo(new McpServerConfig.Auth("radius"));
    }

    @Test
    void alreadyConfiguredGlobalServerNeedsNeitherPromptNorWrite() {
        var configured = http("https://radius.pi.dev/mcp/", new McpServerConfig.Auth("radius"));
        McpConfig.add(tmp.resolve("mcp.json"), "radius", configured);
        var before = tmp.resolve("mcp.json").toFile().lastModified();

        var decision = RadiusMcpOffer.prepare(tmp, tmp);

        assertThat(decision.alreadyConfigured()).isTrue();
        assertThat(RadiusMcpOffer.apply(tmp, decision)).isFalse();
        assertThat(tmp.resolve("mcp.json").toFile().lastModified()).isEqualTo(before);
    }

    @Test
    void applyAddsPreparedServerToGlobalConfig() {
        var decision = RadiusMcpOffer.decisionFor("radius", null, List.of());

        assertThat(RadiusMcpOffer.apply(tmp, decision)).isTrue();
        var loaded = McpConfig.load(tmp, tmp, false);
        assertThat(loaded.errors()).isEmpty();
        assertThat(loaded.servers()).singleElement().satisfies(entry -> {
            assertThat(entry.name()).isEqualTo("radius");
            assertThat(((McpServerConfig.Http) entry.config()).auth())
                .isEqualTo(new McpServerConfig.Auth("radius"));
        });
    }

    private static McpServerConfig.Http http(String url, McpServerConfig.Auth auth) {
        return new McpServerConfig.Http(null, null, null, null, null, null,
            url, null, null, auth);
    }
}
