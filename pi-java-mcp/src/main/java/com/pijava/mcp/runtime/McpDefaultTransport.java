package com.pijava.mcp.runtime;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.AuthProvider;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.transport.McpTransport;
import com.pijava.mcp.transport.StdioTransport;
import com.pijava.mcp.transport.StdioTransportOptions;
import com.pijava.mcp.transport.http.StreamableHttpTransport;
import com.pijava.mcp.transport.http.StreamableHttpTransportOptions;

/**
 * The transport a configured server is opened with (pi {@code createDefaultTransport},
 * {@code runtime.ts:97-121}).
 */
public final class McpDefaultTransport {

    private McpDefaultTransport() {
    }

    /**
     * Build the transport for one server.
     *
     * @param entry the configured server
     * @param cwd the directory the session runs in, used for stdio children and for the roots
     *            offered to the server
     * @param authProvider the credential provider, or {@code null}
     * @param resolver resolves {@code $NAME} and {@code !cmd} values
     * @return the transport, not yet started
     */
    public static McpTransport create(McpServerEntry entry, Path cwd,
                                      @Nullable AuthProvider authProvider,
                                      ConfigValueResolver resolver) {
        var description = "MCP server \"" + entry.name() + "\"";
        if (entry.config() instanceof McpServerConfig.Http http) {
            return new StreamableHttpTransport(new StreamableHttpTransportOptions(
                    http.url(),
                    resolver.resolveHeadersOrThrow(http.headers(), description, null),
                    null, true, 0, authProvider, null));
        }
        var stdio = (McpServerConfig.Stdio) entry.config();
        var env = new LinkedHashMap<String, String>();
        for (var variable : (stdio.env() == null ? Map.<String, String>of() : stdio.env()).entrySet()) {
            env.put(variable.getKey(), resolver.resolveOrThrow(variable.getValue(),
                    description + " env \"" + variable.getKey() + "\"", null));
        }
        return new StdioTransport(new StdioTransportOptions(
                expandHome(stdio.command()),
                stdio.args() == null ? List.of()
                        : stdio.args().stream().map(McpDefaultTransport::expandHome).toList(),
                // path.resolve is absolute-ize plus normalize; Path.resolve only concatenates.
                cwd.toAbsolutePath().resolve(expandHome(stdio.cwd() == null ? "." : stdio.cwd()))
                        .normalize().toString(),
                env,
                true,
                StdioTransportOptions.Stderr.PIPE,
                0,
                0));
    }

    /**
     * {@code ~} and {@code ~/…} (also {@code ~\…} on Windows) name the home directory, like in a
     * shell ({@code runtime.ts:89-95}).
     *
     * @param value the configured path
     * @return the path with a leading home reference expanded
     */
    public static String expandHome(String value) {
        if ("~".equals(value)) {
            return System.getProperty("user.home");
        }
        var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (value.startsWith("~/") || (windows && value.startsWith("~\\"))) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }
}
