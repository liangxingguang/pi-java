package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;

/**
 * Directory listing tool. Aligned with pi's ls tool.
 *
 * <p>Schema: { path?: String, recursive?: Boolean }
 * Lists files and directories with size, type, and modified time.</p>
 */
public final class LsTool {
    private LsTool() {}

    public record LsInput(Optional<String> path, Optional<Boolean> recursive) {}

    public static AgentTool<LsInput, Void> create() {
        return new AgentTool<>() {
            @Override public String name() { return "ls"; }
            @Override public String label() { return "ls"; }
            @Override public String description() {
                return "List files and directories in a given path. "
                    + "Shows file size, type, and modification time.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string",
                            "description", "Directory path to list (defaults to cwd)"),
                        "recursive", Map.of("type", "boolean",
                            "description", "Whether to list recursively")
                    ),
                    "required", List.of()
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

            @Override
            public LsInput prepareArguments(Map<String, Object> raw) {
                Optional<String> path = Optional.ofNullable((String) raw.get("path"));
                Optional<Boolean> recursive = Optional.empty();
                Object recObj = raw.get("recursive");
                if (recObj instanceof Boolean b) recursive = Optional.of(b);
                return new LsInput(path, recursive);
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, LsInput params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                String dirPath = params.path().orElse(context.cwd());
                String absolutePath = PathUtils.resolveToolPath(context, dirPath);
                boolean recursive = params.recursive().orElse(false);

                var entries = context.fs().listDir(absolutePath, recursive);
                if (entries.isEmpty()) {
                    return ToolResult.success("(empty directory)");
                }

                var sb = new StringBuilder();
                for (var entry : entries) {
                    String prefix = "dir".equals(entry.kind()) ? "[DIR]  " : "[FILE] ";
                    sb.append(prefix)
                        .append(TruncationUtils.formatSize(entry.size())).append("  ")
                        .append(entry.path()).append("\n");
                }
                return ToolResult.success(sb.toString());
            }
        };
    }
}
