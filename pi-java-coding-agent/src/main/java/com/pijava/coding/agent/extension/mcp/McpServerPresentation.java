package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerConfigs;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.runtime.McpServerConnection;

/**
 * How a configured server reads in a list or a report (pi {@code index.ts:113-272}).
 *
 * <p>The MCP tool listing (the {@code pi-java mcp list} command) writes its own lines; these are
 * the {@code /mcp} manager's and the start-up report's, which read differently on purpose.</p>
 */
public final class McpServerPresentation {

    /** What each exposure means, in the manager's own words ({@code index.ts:113-117}). */
    public static final Map<McpExposure, String> EXPOSURE_DESCRIPTIONS =
            Map.of(
                    McpExposure.CODEMODE,
                    "called from codemode scripts, which find them with searchTools()",
                    McpExposure.DEFERRED,
                    "not declared until tool_search loads them, then called directly;"
                            + " no codemode needed",
                    McpExposure.DIRECT,
                    "declared to the model like built-in tools");

    /** What an action the command does not know prints ({@code index.ts:274}). */
    public static final String MCP_USAGE =
            "Usage: /mcp, /mcp login [server], /mcp logout [server], /mcp reconnect [server]";

    /** The codemode helpers a script calls, any of which means it may need a server. */
    private static final Pattern SCRIPT_HELPERS =
            Pattern.compile("\\b(searchTools|describeNamespace|describeTool|ALL_TOOLS)\\b");

    private McpServerPresentation() {
    }

    /**
     * The first line of a text ({@code index.ts:123-125}).
     *
     * @param text the text
     * @return everything before the first newline
     */
    public static String firstLine(String text) {
        var separator = text.indexOf('\n');
        return separator < 0 ? text : text.substring(0, separator);
    }

    /**
     * Whether the server is configured to run ({@code index.ts:127-129}).
     *
     * @param entry the configured server
     * @return {@code false} only when {@code enabled} is explicitly {@code false}
     */
    public static boolean isEnabled(McpServerEntry entry) {
        return !Boolean.FALSE.equals(entry.config().enabled());
    }

    /**
     * The server's exposure ({@code index.ts:131-133}).
     *
     * @param entry the configured server
     * @return the configured exposure, or {@code codemode}
     */
    public static McpExposure exposureOf(McpServerEntry entry) {
        var exposure = entry.config().exposure();
        return exposure == null ? McpExposure.CODEMODE : exposure;
    }

    /**
     * Exposures the server's tools can have, known from its config before it connects
     * ({@code index.ts:135-138}).
     *
     * @param entry the configured server
     * @return the server's exposure plus every per-tool override
     */
    public static Set<McpExposure> configuredExposures(McpServerEntry entry) {
        var exposures = new LinkedHashSet<McpExposure>();
        exposures.add(exposureOf(entry));
        var perTool = entry.config().toolExposure();
        if (perTool != null) {
            exposures.addAll(perTool.values());
        }
        return exposures;
    }

    /**
     * Whether some of the server's tools are declared to the model, so the first prompt waits for
     * them ({@code index.ts:140-143}).
     *
     * @param entry the configured server
     * @return whether any exposure is {@code direct}
     */
    public static boolean hasDirectTools(McpServerEntry entry) {
        return configuredExposures(entry).contains(McpExposure.DIRECT);
    }

    /**
     * Whether some of the server's tools are reached through codemode or tool_search
     * ({@code index.ts:145-149}).
     *
     * @param entry the configured server
     * @return whether any exposure is {@code codemode} or {@code deferred}
     */
    public static boolean hasIndirectTools(McpServerEntry entry) {
        var exposures = configuredExposures(entry);
        return exposures.contains(McpExposure.CODEMODE) || exposures.contains(McpExposure.DEFERRED);
    }

    /**
     * Whether a codemode script needs the server: it names the server's namespace, or calls a
     * helper that may name it in another form ({@code index.ts:219-226}).
     *
     * @param code the script source
     * @param server the MCP server name
     * @return whether the script may need the server
     */
    public static boolean scriptNeedsServer(String code, String server) {
        return SCRIPT_HELPERS.matcher(code).find() || code.contains(McpServerConfigs.namespace(server));
    }

    /**
     * Short state for lists and the start-up report ({@code index.ts:228-249}).
     *
     * @param entry the configured server
     * @param connection the connection, or {@code null} before one was started
     * @param withError appends the first line of a failure
     * @return the state, as the manager writes it
     */
    public static String describeState(McpServerEntry entry,
                                       @Nullable McpServerConnection connection,
                                       boolean withError) {
        if (!isEnabled(entry)) {
            return "disabled";
        }
        if (connection == null) {
            return "starting";
        }
        return describeState(connection.state().wire(), connection.error(),
                connection.tools().size(), connection.resources().size(), withError);
    }

    /**
     * Short state from the pieces a connection reports ({@code index.ts:229-249}).
     *
     * @param state the connection state as it is written on the wire
     * @param error the failure message, when there is one
     * @param toolCount tools the server offers
     * @param resourceCount resources it listed
     * @param withError appends the first line of a failure
     * @return the state, as the manager writes it
     */
    public static String describeState(String state, @Nullable String error, int toolCount,
                                       int resourceCount, boolean withError) {
        return switch (state) {
            case "needs-auth" -> "needs sign-in";
            case "failed" -> withError
                    ? "failed: " + firstLine(error == null ? "unknown error" : error)
                    : "failed";
            case "connected" -> {
                var resourcePart = resourceCount > 0
                        ? " · " + resourceCount + (resourceCount == 1 ? " resource" : " resources") : "";
                yield "connected · " + toolCount + (toolCount == 1 ? " tool" : " tools") + resourcePart;
            }
            case "connecting" -> "connecting…";
            default -> state;
        };
    }

    /**
     * Servers that need the user first ({@code index.ts:251-266}).
     *
     * @param entry the configured server
     * @param connection the connection, or {@code null} before one was started
     * @return the rank; a connected server is last, a sign-in first
     */
    public static int attentionRank(McpServerEntry entry, @Nullable McpServerConnection connection) {
        return attentionRank(isEnabled(entry),
                connection == null ? null : connection.state().wire());
    }

    /**
     * Servers that need the user first, from the pieces ({@code index.ts:251-266}).
     *
     * @param enabled whether the server is configured to run
     * @param state the connection state, or {@code null} before one was started
     * @return the rank; a connected server is last, a sign-in first
     */
    public static int attentionRank(boolean enabled, @Nullable String state) {
        if (!enabled) {
            return 5;
        }
        if (state == null) {
            return 3;
        }
        return switch (state) {
            case "needs-auth" -> 0;
            case "failed" -> 1;
            case "disconnected" -> 2;
            case "connected" -> 4;
            default -> 3;
        };
    }

    /**
     * The server's endpoint or command line ({@code index.ts:268-272}).
     *
     * @param entry the configured server
     * @return the URL, or the command with its arguments
     */
    public static String describeTransport(McpServerEntry entry) {
        var config = entry.config();
        if (config instanceof McpServerConfig.Http http) {
            return http.url();
        }
        var stdio = (McpServerConfig.Stdio) config;
        var parts = new ArrayList<String>();
        parts.add(stdio.command());
        if (stdio.args() != null) {
            parts.addAll(stdio.args());
        }
        return String.join(" ", parts);
    }
}
