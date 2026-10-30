package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;

import com.pijava.ai.provider.builtin.RadiusProvider;
import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import org.jspecify.annotations.Nullable;

/** Prepares and applies the post-login Radius MCP configuration offer. */
public final class RadiusMcpOffer {

    private static final String MCP_URL = RadiusProvider.DEFAULT_GATEWAY + "/mcp";

    /** Fully prepared choice; alreadyConfigured means no prompt or write is needed. */
    public record Decision(String name, McpServerConfig.Http config, boolean alreadyConfigured) {}

    private RadiusMcpOffer() {}

    /** Load global MCP state (project config is deliberately excluded) and prepare an offer. */
    public static Decision prepare(Path agentDir, Path cwd) {
        var servers = McpConfig.load(agentDir, cwd, false).servers();
        var existing = servers.stream()
            .filter(entry -> entry.config() instanceof McpServerConfig.Http http
                && normalize(http.url()).equals(normalize(MCP_URL)))
            .findFirst().orElse(null);
        return decisionFor("radius", existing == null ? null : (McpServerConfig.Http) existing.config(),
            servers);
    }

    /** Build the updated HTTP config while preserving every unrelated field. */
    public static Decision decisionFor(String providerId,
                                       McpServerConfig.@Nullable Http existing,
                                       List<McpServerEntry> allServers) {
        String name = null;
        if (existing != null) {
            name = allServers.stream().filter(entry -> entry.config() == existing
                    || entry.config().equals(existing))
                .map(McpServerEntry::name).findFirst().orElse(null);
        }
        if (existing != null && existing.auth() != null
                && providerId.equals(existing.auth().provider())) {
            return new Decision(name == null ? "radius" : name, existing, true);
        }
        if (name == null) {
            name = allServers.stream().anyMatch(entry -> entry.name().equals("radius"))
                ? "radius-mcp" : "radius";
        }
        var auth = new McpServerConfig.Auth(providerId);
        var config = existing == null
            ? new McpServerConfig.Http(null, null, null, null, null, null,
                MCP_URL, null, null, auth)
            : new McpServerConfig.Http(existing.type(), existing.exposure(), existing.description(),
                existing.toolExposure(), existing.enabled(), existing.timeout(), existing.url(),
                existing.headers(), null, auth);
        return new Decision(name, config, false);
    }

    /** Write a non-noop decision; true means the caller should reload the session. */
    public static boolean apply(Path agentDir, Decision decision) {
        if (decision.alreadyConfigured()) {
            return false;
        }
        McpConfig.add(agentDir.resolve("mcp.json"), decision.name(), decision.config());
        return true;
    }

    private static String normalize(String url) {
        var end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
