package com.pijava.mcp.config;

import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The result of {@link McpConfig#load} ({@code config.ts:65-72}).
 *
 * @param servers            servers in file order: global entries with project entries
 *                           replacing them in place
 * @param autoEnableCodemode top-level {@code autoEnableCodemode}, or {@code null} when unset
 * @param errors             validation errors, each formatted {@code <path>: <message>}
 * @param projectConfig      the project {@code mcp.json} when the project is trusted,
 *                           where {@code /mcp} saves project overrides
 */
public record LoadedMcpConfig(List<McpServerEntry> servers, @Nullable Boolean autoEnableCodemode,
                              List<String> errors, @Nullable Path projectConfig) {
}
