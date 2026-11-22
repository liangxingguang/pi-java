package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.Optional;

import com.pijava.ai.provider.builtin.RadiusProvider;
import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerConfigs;
import org.jspecify.annotations.Nullable;

/**
 * The global {@code mcp.json} entry that makes the Radius MCP server use the login of
 * {@code providerId} (pi {@code core/radius-mcp.ts}, anchor {@code a00a5048d}).
 *
 * <p>{@code /login} asks before applying the offer; {@code auth login} applies it without
 * asking because the login is what the server needs.</p>
 */
public final class RadiusMcpPlan {

    /** A prepared change to the global mcp.json. */
    public record Plan(Path mcpPath, String name, McpServerConfig.Http config) {}

    private static final String MCP_URL = RadiusProvider.DEFAULT_GATEWAY + "/mcp";

    private RadiusMcpPlan() {}

    /**
     * Plan the change, adding the server when missing.
     *
     * @return empty when a global server already uses this login
     */
    public static Optional<Plan> plan(String providerId, Path agentDir, Path cwd) {
        var servers = McpConfig.load(agentDir, cwd, false).servers();
        var existing = servers.stream()
            .filter(entry -> entry.config() instanceof McpServerConfig.Http http
                && normalize(http.url()).equals(normalize(MCP_URL)))
            .findFirst().orElse(null);

        var existingHttp = existing == null ? null : (McpServerConfig.Http) existing.config();
        if (existingHttp != null && existingHttp.auth() != null
                && providerId.equals(existingHttp.auth().provider())) {
            return Optional.empty();
        }

        var name = existing == null
            ? availableName(servers) : existing.name();
        var auth = new McpServerConfig.Auth(providerId);
        // `auth` replaces the MCP OAuth sign-in.
        var config = existingHttp == null
            ? new McpServerConfig.Http(null, null, null, null, null, null,
                MCP_URL, null, null, auth)
            : new McpServerConfig.Http(existingHttp.type(), existingHttp.exposure(),
                existingHttp.description(), existingHttp.toolExposure(), existingHttp.enabled(),
                existingHttp.timeout(), existingHttp.url(), existingHttp.headers(), null, auth);
        return Optional.of(new Plan(agentDir.resolve("mcp.json"), name, config));
    }

    /** pi 的命名轮回：radius → radius-mcp → radius-mcp-2 … */
    static String availableName(java.util.List<com.pijava.mcp.config.McpServerEntry> servers) {
        var namespaces = servers.stream().map(entry -> McpServerConfigs.namespace(entry.name()))
            .toList();
        var name = "radius";
        for (var suffix = 1; namespaces.contains(McpServerConfigs.namespace(name)); suffix++) {
            name = suffix == 1 ? "radius-mcp" : "radius-mcp-" + suffix;
        }
        return name;
    }

    private static String normalize(@Nullable String url) {
        if (url == null) {
            return "";
        }
        var end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
