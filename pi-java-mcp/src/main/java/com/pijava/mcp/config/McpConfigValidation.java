package com.pijava.mcp.config;

/**
 * The result of validating one server entry ({@code mcp-servers.ts:221}) — pi returns
 * a configuration or an error message.
 */
public sealed interface McpConfigValidation {

    /** The validated configuration (exposure names already resolved). */
    record Valid(McpServerConfig config) implements McpConfigValidation {
    }

    /** The message pi returns instead of a configuration. */
    record Invalid(String message) implements McpConfigValidation {
    }
}
