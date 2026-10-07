package com.pijava.coding.agent.subcommand;

import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.runtime.ConfigValueResolver;
import com.pijava.mcp.runtime.McpConnectionOptions;
import com.pijava.mcp.runtime.McpDefaultTransport;
import com.pijava.mcp.runtime.McpOAuthCredentialStore;
import com.pijava.mcp.runtime.McpServerConnection;
import com.pijava.mcp.runtime.McpServerLog;

/**
 * What the {@code mcp} subcommands share: how a connection is built, how a server is described,
 * and the number and message helpers (pi {@code cli.ts:109-127}).
 */
final class McpCommandSupport {

    private McpCommandSupport() {
    }
    static McpServerConnection connection(McpServerEntry entry, McpCommand.Options options,
                                          McpOAuthCredentialStore credentials) {
        return new McpServerConnection(new McpConnectionOptions(
                entry, options.cwd(),
                (server, cwd, provider) ->
                        McpDefaultTransport.create(server, cwd, provider, ConfigValueResolver.DEFAULT),
                credentials, null, connection -> {
                }, null, new McpServerLog(options.agentDir().resolve("mcp.log")),
                options.clientName(), options.clientVersion(), ConfigValueResolver.DEFAULT));
    }

    /** {@code cli.ts:113-116}. */
    static String describeTransport(McpServerEntry entry) {
        if (entry.config() instanceof McpServerConfig.Http http) {
            return http.url();
        }
        var stdio = (McpServerConfig.Stdio) entry.config();
        var parts = new java.util.ArrayList<String>();
        parts.add(stdio.command());
        if (stdio.args() != null) {
            parts.addAll(stdio.args());
        }
        return String.join(" ", parts);
    }

    static String scopeName(McpServerEntry entry) {
        return entry.scope() == null ? "global" : entry.scope().name().toLowerCase(Locale.ROOT);
    }

    /** Whether the project directory is trusted (pi's {@code ProjectTrustStore}). */
    static boolean projectTrusted(McpCommand.Options options) {
        return SettingsManager.load(options.agentDir(), options.cwd(), null).isProjectTrusted();
    }

    static boolean hasAuthorizationHeader(@Nullable Map<String, String> headers) {
        return headers != null && headers.keySet().stream()
                .anyMatch(header -> "authorization".equals(header.toLowerCase(Locale.ROOT)));
    }

    /**
     * {@code Number(value)} for a numeric option, or {@code null} when it is not a number
     * (JavaScript's {@code NaN}).
     */
    static @Nullable Double numberOrNull(String value) {
        try {
            var parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException expected) {
            return null;
        }
    }

    /**
     * {@code JSON.stringify(Number(value))}: an integral number loses its fraction, and
     * {@code NaN} becomes {@code null} — which is what the validator then reports.
     */
    static com.fasterxml.jackson.databind.JsonNode numberNode(@Nullable Double value) {
        if (value == null) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return com.fasterxml.jackson.databind.node.LongNode.valueOf(value.longValue());
        }
        return com.fasterxml.jackson.databind.node.DoubleNode.valueOf(value);
    }

    /**
     * Whether a pasted redirect URL is possible: a terminal, and no injected browser opener.
     * pi: {@code process.stdin.isTTY === true && options.openUrl === undefined}.
     *
     * @param options the command environment
     * @return whether stdin may be read
     */
    static boolean interactive(McpCommand.Options options) {
        return options.terminal() && options.openUrl() == null;
    }

    static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
