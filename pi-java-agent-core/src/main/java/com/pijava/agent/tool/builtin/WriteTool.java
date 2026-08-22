package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.PathUtils;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;

/**
 * File writing tool. Creates parent directories, overwrites existing files.
 * Aligned with pi's {@code createWriteTool}.
 *
 * <p>Schema: { path: String, content: String }. Writes to the same canonical
 * path are serialized through {@link FileMutationQueue} so concurrent tool
 * calls never interleave.</p>
 */
public final class WriteTool {
    private WriteTool() {}

    /** Serializes writes to the same canonical path across concurrent tool calls. */
    private static final FileMutationQueue QUEUE = new FileMutationQueue();

    public record WriteInput(String path, String content) {}

    /** Create the write tool. */
    public static AgentTool<WriteInput, Void> create() {
        return new AgentTool<>() {
            @Override public String name() { return "write"; }
            @Override public String label() { return "write"; }
            @Override public String description() {
                return "Write content to a file. Creates the file if it doesn't exist, "
                    + "overwrites if it does. Automatically creates parent directories.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string",
                            "description", "Path to the file to write (relative or absolute)"),
                        "content", Map.of("type", "string",
                            "description", "Content to write to the file")
                    ),
                    "required", List.of("path", "content")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

            @Override
            public WriteInput prepareArguments(Map<String, Object> raw) {
                return new WriteInput((String) raw.get("path"), (String) raw.get("content"));
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, WriteInput params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                String absolutePath = PathUtils.resolveToolPath(context, params.path());
                QUEUE.withQueue(absolutePath, () -> {
                    context.fs().writeFile(absolutePath, params.content());
                    return null;
                });
                return ToolResult.success("Successfully wrote "
                    + params.content().length() + " bytes to " + params.path());
            }
        };
    }
}
