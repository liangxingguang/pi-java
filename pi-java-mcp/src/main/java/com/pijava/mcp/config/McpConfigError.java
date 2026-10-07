package com.pijava.mcp.config;

/**
 * An {@code mcp.json} that cannot be read, parsed, or edited
 * ({@code config.ts:181,235}).
 *
 * <p>pi throws a plain {@code Error} here; a named type lets the CLI tell a
 * configuration problem from a programming one.</p>
 */
public class McpConfigError extends RuntimeException {

    /** Create an error with a message. */
    public McpConfigError(String message) {
        super(message);
    }

    /** Create an error with a message and a cause. */
    public McpConfigError(String message, Throwable cause) {
        super(message, cause);
    }
}
