package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.FileSystem;
import com.pijava.agent.tool.PathUtils;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;

import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.StrictMode;

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

    /** Serializes writes to the same canonical path across concurrent tool calls（与 edit 共享，pi 模块级单例）。 */
    private static final FileMutationQueue QUEUE = FileMutationQueue.shared();

    /** 字符串级 {@code dirname}：兼容本地与远程路径分隔符。 */
    private static String parentOf(String absolutePath) {
        int index = Math.max(absolutePath.lastIndexOf('/'), absolutePath.lastIndexOf('\\'));
        return index > 0 ? absolutePath.substring(0, index) : null;
    }

    public record WriteInput(String path, String content) {}

    /**
     * 文件写入接缝（对位 pi {@code WriteOperations}，B287）：默认实现走
     * {@link ToolContext#fs()}；远程后端可注入自己的实现。
     */
    public interface Operations {
        void writeFile(String absolutePath, String content) throws IOException;
        void mkdir(String directoryPath) throws IOException;

        static Operations from(ToolContext context) {
            FileSystem fs = context.fs();
            return new Operations() {
                @Override public void writeFile(String absolutePath, String content)
                        throws IOException {
                    fs.writeFile(absolutePath, content);
                }
                @Override public void mkdir(String directoryPath) {
                    // fs.writeFile 自身创建父目录（FileSystem 契约）。
                }
            };
        }
    }

    /** Create the write tool. */
    public static AgentTool<WriteInput, Void> create() {
        return create(null);
    }

    /** Create the write tool with an injectable filesystem seam. */
    public static AgentTool<WriteInput, Void> create(Operations operations) {
        return new AgentTool<>() {
            @Override public String name() { return "write"; }
            @Override public String label() { return "write"; }
            @Override public String promptSnippet() {
                // pi writeToolSystemPromptContribution.snippet（write.ts:17）逐字。
                return "Create or overwrite files";
            }
            @Override public List<String> promptGuidelines() {
                // pi writeToolSystemPromptContribution.guidelines（write.ts:18）逐字。
                return List.of("Use write only for new files or complete rewrites.");
            }
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

            // 原 docs/66：pi 的 write 默认 strict-prefer。
            @Override public JsonSchemaSampling constrainedSampling() {
                return new JsonSchemaSampling(StrictMode.PREFER);
            }

            @Override
            public WriteInput prepareArguments(Map<String, Object> raw) {
                return new WriteInput((String) raw.get("path"), (String) raw.get("content"));
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, WriteInput params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                String absolutePath = PathUtils.resolveToolPath(context, params.path());
                Operations ops = operations != null ? operations : Operations.from(context);
                // 按字符串取父目录：远程 POSIX 路径不应经本地 Path 解析（Windows 会改写分隔符）。
                String parent = parentOf(absolutePath);
                QUEUE.withQueue(absolutePath, () -> {
                    if (parent != null) {
                        ops.mkdir(parent);
                    }
                    ops.writeFile(absolutePath, params.content());
                    return null;
                });
                return ToolResult.success("Successfully wrote "
                    + params.content().length() + " bytes to " + params.path());
            }
        };
    }
}
