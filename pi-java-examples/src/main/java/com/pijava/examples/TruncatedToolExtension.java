package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.TruncationUtils;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/truncated-tool.ts:1-195} 移植（docs/54 §2.7）——
 * rg 工具通过注入式 {@link RgExecutor} 执行（默认走 ctx.exec），结果按 TruncationUtils 截断。
 */
public final class TruncatedToolExtension implements PiExtension {

    /** 可注入的 rg 执行后端；测试 fake，生产走 ctx.exec。 */
    @FunctionalInterface
    interface RgExecutor {
        /** 返回 \n 分隔的 `file:line:text` 行；抛异常视为失败。 */
        String run(String pattern, String path, String glob, String cwd);
    }

    @Override
    public String name() {
        return "truncated-tool";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new RgTool(this, (pattern, path, glob, cwd) -> {
            var args = new ArrayList<String>(List.of("rg", "--line-number", "--color=never"));
            if (glob != null && !glob.isEmpty()) {
                args.add("--glob");
                args.add(glob);
            }
            args.add(pattern);
            args.add(path == null || path.isEmpty() ? "." : path);
            var result = ctx.exec("rg", args.subList(1, args.size()),
                new ExtensionExec.ExecOptions(null, null, cwd, 100L * 1024 * 1024));
            if (result.code() == 1) {
                return ""; // 无匹配
            }
            if (result.code() != 0) {
                throw new IllegalStateException(result.stderr().isBlank()
                    ? "exit code " + result.code() : result.stderr().trim());
            }
            return result.stdout();
        }));
    }

    /** 统计匹配行数（每行一条）。 */
    static int matchCount(String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }
        return (int) output.lines().count();
    }

    /** pi L125-128：截断通知逐字（4 段）。 */
    static String truncationNotice(TruncationUtils.TruncationResult t) {
        var skippedLines = t.totalLines() - t.outputLines();
        var skippedBytes = t.totalBytes() - t.outputBytes();
        return "\n\n[Output truncated: showing " + t.outputLines() + " of " + t.totalLines()
            + " lines (" + TruncationUtils.formatSize(t.outputBytes()) + " of "
            + TruncationUtils.formatSize(t.totalBytes()) + ")." 
            + " " + skippedLines + " lines (" + TruncationUtils.formatSize(skippedBytes)
            + ") omitted. Full output saved to: <tempFile>]";
    }

    /** 可执行执行路径（纯逻辑，RESULT 组装）。 */
    ToolResult<Map<String, Object>> run(RgExecutor exec, String pattern, String path,
            String glob, String cwd) {
        String output;
        try {
            output = exec.run(pattern, path, glob, cwd);
        } catch (RuntimeException e) {
            var msg = "ripgrep failed: " + e.getMessage();
            return new ToolResult<>(List.of(new ContentBlock.TextContent(msg)),
                Map.of("error", true), null, false, List.of(), true, null);
        }
        if (output == null || output.isBlank()) {
            return new ToolResult<>(List.of(new ContentBlock.TextContent("No matches found")),
                Map.of("matchCount", 0), null, false, List.of());
        }
        var count = matchCount(output);
        var truncation = TruncationUtils.truncateHead(output);
        var text = truncation.content();
        if (truncation.truncated()) {
            text = text + truncationNotice(truncation);
        }
        return new ToolResult<>(List.of(new ContentBlock.TextContent(text)),
            Map.of("matchCount", count, "truncated", truncation.truncated()),
            null, false, List.of());
    }

    static final class RgTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final TruncatedToolExtension ext;
        private final RgExecutor exec;

        RgTool(TruncatedToolExtension ext, RgExecutor exec) {
            this.ext = ext;
            this.exec = exec;
        }

        @Override public String name() { return "rg"; }
        @Override public String label() { return "ripgrep"; }
        @Override public String description() {
            return "Search file contents using ripgrep. Output is truncated to "
                + TruncationUtils.DEFAULT_MAX_LINES + " lines or "
                + TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                + " (whichever is hit first). If truncated, full output is saved to a temp file.";
        }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
                "pattern", JsonSchemas.obj("type", "string", "description", "Search pattern (regex)"),
                "path", JsonSchemas.obj("type", "string",
                    "description", "Directory to search (default: current directory)"),
                "glob", JsonSchemas.obj("type", "string",
                    "description", "File glob pattern, e.g. '*.ts'")));
        }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var pattern = params.get("pattern") instanceof String s ? s : "";
            var path = params.get("path") instanceof String s ? s : null;
            var glob = params.get("glob") instanceof String s ? s : null;
            return ext.run(exec, pattern, path, glob, null);
        }
    }
}
