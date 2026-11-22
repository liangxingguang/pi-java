package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.mcp.config.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RadiusMcpPlanTest {

    private static final String URL = "https://radius.pi.dev/mcp";

    @TempDir
    Path tmp;

    @Test
    void addsTheServerWhenMissing() throws Exception {
        var plan = RadiusMcpPlan.plan("radius", tmp, tmp).orElseThrow();

        assertThat(plan.name()).isEqualTo("radius");
        assertThat(plan.mcpPath()).isEqualTo(tmp.resolve("mcp.json"));
        assertThat(plan.config().url()).isEqualTo(URL);
        assertThat(plan.config().auth()).isEqualTo(new McpServerConfig.Auth("radius"));
        assertThat(plan.config().oauth()).isNull();
    }

    @Test
    void nothingChangesWhenTheLoginIsAlreadyConfigured() throws Exception {
        writeServers(Map.of("radius", http(URL, new McpServerConfig.Auth("radius"))));

        assertThat(RadiusMcpPlan.plan("radius", tmp, tmp)).isEmpty();
    }

    @Test
    void existingServerKeepsItsFieldsAndLosesOauth() throws Exception {
        var existing = new McpServerConfig.Http(null, null, "team gateway", null,
            Boolean.TRUE, 30, URL + "/", Map.of("x-key", "v"),
            new McpServerConfig.OAuth(null, null, null, null, null, null, null, null),
            new McpServerConfig.Auth("openrouter"));
        writeServers(Map.of("team-radius", existing));

        var plan = RadiusMcpPlan.plan("radius", tmp, tmp).orElseThrow();

        assertThat(plan.name()).isEqualTo("team-radius");
        assertThat(plan.config().url()).isEqualTo(URL + "/");
        assertThat(plan.config().description()).isEqualTo("team gateway");
        assertThat(plan.config().timeout()).isEqualTo(30);
        assertThat(plan.config().headers()).containsEntry("x-key", "v");
        assertThat(plan.config().auth()).isEqualTo(new McpServerConfig.Auth("radius"));
        assertThat(plan.config().oauth()).isNull();
    }

    @Test
    void nameCollisionsFollowTheRadiusSuffixCycle() throws Exception {
        writeServers(Map.of(
            "radius", http("https://example.test/mcp", null),
            "radius-mcp", http("https://example.test/other", null)));

        var plan = RadiusMcpPlan.plan("radius", tmp, tmp).orElseThrow();

        assertThat(plan.name()).isEqualTo("radius-mcp-2");
    }

    private static McpServerConfig.Http http(String url, McpServerConfig.Auth auth) {
        return new McpServerConfig.Http(null, null, null, null, null, null,
            url, null, null, auth);
    }


    private void writeServers(Map<String, McpServerConfig> servers) {
        servers.forEach((name, config) -> com.pijava.mcp.config.McpConfig.add(
            tmp.resolve("mcp.json"), name, config));
    }
}
