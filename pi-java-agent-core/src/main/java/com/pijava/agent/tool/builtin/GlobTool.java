package com.pijava.agent.tool.builtin;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;

/**
 * Glob-based file matching tool. Aligned with pi's glob tool.
 *
 * <p>Schema: { pattern: String, path?: String }
 * Uses Java NIO PathMatcher.</p>
 */
public final class GlobTool {
    private GlobTool() {}

    public record GlobInput(String pattern, Optional<String> path) {}

    public static AgentTool<GlobInput, Void> create() {
        return new AgentTool<>() {
            @Override public String name() { return "glob"; }
            @Override public String label() { return "glob"; }
            @Override public String description() {
                return "Find files matching a glob pattern. Returns matching file paths.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pattern", Map.of("type", "string",
                            "description", "Glob pattern to match files (e.g. **/*.java)"),
                        "path", Map.of("type", "string",
                            "description", "Directory to search in (defaults to cwd)")
                    ),
                    "required", List.of("pattern")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

            @Override
            @SuppressWarnings("unchecked")
            public GlobInput prepareArguments(Map<String, Object> raw) {
                String pattern = (String) raw.get("pattern");
                Optional<String> path = Optional.ofNullable((String) raw.get("path"));
                return new GlobInput(pattern, path);
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, GlobInput params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                String searchPath = params.path().orElse(context.cwd());
                String absolutePath = PathUtils.resolveToolPath(context, searchPath);
                Path dir = Path.of(absolutePath);
                if (!Files.isDirectory(dir)) {
                    throw new IllegalArgumentException("Path is not a directory: "
                        + searchPath);
                }

                var matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + params.pattern());

                var sb = new StringBuilder();
                try (var stream = Files.walk(dir)) {
                    stream.filter(p -> !Files.isDirectory(p))
                        .filter(p -> matcher.matches(dir.relativize(p)))
                        .forEach(p -> sb.append(p.toAbsolutePath()).append("\n"));
                }

                if (sb.isEmpty()) {
                    return ToolResult.success("No files matched pattern: "
                        + params.pattern());
                }
                return ToolResult.success(sb.toString().stripTrailing());
            }
        };
    }
}
