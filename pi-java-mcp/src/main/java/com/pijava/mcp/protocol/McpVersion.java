package com.pijava.mcp.protocol;

import java.util.List;

/**
 * MCP protocol versions ({@code types.ts:4-9}).
 */
public final class McpVersion {

    /** Latest version the client requests. */
    public static final String LATEST = "2025-11-25";

    /** Versions the client accepts from a server. */
    public static final List<String> SUPPORTED = List.of(
            LATEST, "2025-06-18", "2025-03-26", "2024-11-05");

    private McpVersion() {
    }
}
