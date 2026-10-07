package com.pijava.mcp.config;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * One server of the loaded configuration ({@code config.ts:51-63}).
 *
 * @param name     server name as written in {@code mcp.json}
 * @param config   the validated configuration
 * @param source   the configuration file that defined the entry
 * @param scope    the global or the project {@code mcp.json}
 * @param override project {@code mcp.json} overriding this entry's {@code enabled},
 *                 {@code exposure}, or {@code toolExposure}, or {@code null}
 */
public record McpServerEntry(String name, McpServerConfig config, Path source, McpScope scope,
                             @Nullable Path override) {
}
