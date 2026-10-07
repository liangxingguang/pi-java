package com.pijava.mcp.transport;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Options of a {@link StdioTransport} (stdio.ts:31-44).
 *
 * @param command       executable
 * @param args          arguments
 * @param env           environment overrides
 * @param inheritEnv    whether the child inherits this process's environment
 * @param stderr        stderr routing
 * @param closeTimeoutMs shutdown grace period in ms; {@code <=0} uses the default
 * @param maxMessageBytes maximum frame size; {@code <=0} uses the default
 */
public record StdioTransportOptions(
        String command,
        List<String> args,
        @Nullable Map<String, String> env,
        boolean inheritEnv,
        Stderr stderr,
        long closeTimeoutMs,
        int maxMessageBytes) {

    /** stderr routing (stdio.ts:69-71). */
    public enum Stderr { PIPE, INHERIT, IGNORE }

    /** Minimal options. */
    public StdioTransportOptions(String command) {
        this(command, List.of(), null, true, Stderr.PIPE, 0, 0);
    }

    /** Minimal options with arguments. */
    public StdioTransportOptions(String command, List<String> args) {
        this(command, args, null, true, Stderr.PIPE, 0, 0);
    }
}
