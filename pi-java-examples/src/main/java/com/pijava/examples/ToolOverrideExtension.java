package com.pijava.examples;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/tool-override.ts:1-144} 移植（docs/52 §2.6）：同名覆盖
 * 内置 read（自实现 execute），敏感路径阻断＋访问日志。W4 唯一完整对齐件。
 */
public final class ToolOverrideExtension implements PiExtension {

    static final int MAX_BYTES = 50 * 1024;

    /** pi L33-41：7 条阻断正则（bean 用 matcher.find() ≙ JS p.test）。 */
    static final List<Pattern> BLOCKED = List.of(
        Pattern.compile("\\.env$"),
        Pattern.compile("\\.env\\..+$"),
        Pattern.compile("secrets?\\.(json|yaml|yml|toml)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("credentials?\\.(json|yaml|yml|toml)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/\\.ssh/"),
        Pattern.compile("/\\.aws/"),
        Pattern.compile("/\\.gnupg/"));

    @Override
    public String name() {
        return "tool-override";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new AuditedReadTool(ctx));
        ctx.slashCommands().register(ExampleCommands.command("read-log",
            "View the file access log", args -> showLog(ctx)));
    }

    /** pi L43-45：路径命中任一阻断正则 ⇒ true。 */
    static boolean isBlockedPath(String path) {
        var normalized = path.replace('\\', '/');
        return BLOCKED.stream().anyMatch(p -> p.matcher(normalized).find());
    }

    private void showLog(ExtensionContext ctx) {
        var logFile = logFile(ctx);
        String content;
        try {
            content = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            content = "";
        }
        var lines = content.lines().toList();
        if (lines.isEmpty()) {
            ctx.ui().notify("No access log found", NotifyType.INFO);
            return;
        }
        var tail = lines.subList(Math.max(0, lines.size() - 20), lines.size());
        ctx.ui().notify("Recent file access:\n" + String.join("\n", tail), NotifyType.INFO);
    }

    static Path logFile(ExtensionContext ctx) {
        return Path.of(System.getProperty("pi.java.agentDir", "."), "read-access.log");
    }

    /** 追加一行日志（pi 经 withFileMutationQueue 串行；单线程测试无需队列，D18）。 */
    static void appendLog(ExtensionContext ctx, String path, boolean allowed, String reason) {
        var status = allowed ? "ALLOWED" : "BLOCKED";
        var suffix = reason == null ? "" : " (" + reason + ")";
        var line = "[" + Instant.now() + "] " + status + ": " + path + suffix + "\n";
        try {
            var file = logFile(ctx);
            // pi 走 appendFile（自动建父目录）；java 侧 agentDir 可能是空串 ⇒ parent 为 null
            var parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** pi L72-127：读文件主逻辑（抽为纯函数便于单测，file IO 经 Path）。 */
    static ToolResult<Map<String, Object>> read(ExtensionContext ctx, Path file,
            Number offset, Number limit, boolean blocked) {
        if (blocked) {
            appendLog(ctx, file.toString(), false, "matches blocked pattern");
            var msg = "Access denied: \"" + file + "\" matches a blocked pattern (sensitive file). "
                + "This tool blocks access to .env files, secrets, credentials, "
                + "and SSH/AWS/GPG directories.";
            return new ToolResult<>(List.of(new ContentBlock.TextContent(msg)),
                Map.of("blocked", true), null, false, List.of(), true, null);
        }
        try {
            var text = Files.readString(file, StandardCharsets.UTF_8);
            appendLog(ctx, file.toString(), true, null);
            var lines = text.split("\n", -1);
            int startLine = offset == null ? 0 : Math.max(0, offset.intValue() - 1);
            int endLine = limit == null ? lines.length : Math.min(lines.length, startLine + limit.intValue());
            var sliced = String.join("\n", java.util.Arrays.copyOfRange(lines, startLine, endLine));
            if (sliced.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                sliced = sliced.substring(0, MAX_BYTES) + "\n\n[Output truncated at 50KB]";
            }
            return new ToolResult<>(List.of(new ContentBlock.TextContent(sliced)),
                Map.of("path", file.toString()), null, false, List.of());
        } catch (IOException e) {
            var msg = "Error reading file: " + e.getMessage();
            return new ToolResult<>(List.of(new ContentBlock.TextContent(msg)),
                Map.of("error", true), null, false, List.of(), true, null);
        }
    }

    /** 同名覆盖的 read 工具。 */
    static final class AuditedReadTool implements AgentTool<Map<String, Object>, Map<String, Object>> {

        private final ExtensionContext ctx;

        AuditedReadTool(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override public String name() { return "read"; }
        @Override public String label() { return "read (audited)"; }
        @Override public com.pijava.agent.tool.ExecutionMode executionMode() {
            return new com.pijava.agent.tool.ExecutionMode.Parallel();
        }
        @Override public String description() {
            return "Read the contents of a file with access logging. Some sensitive paths "
                + "(.env, secrets, credentials) are blocked.";
        }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
                "path", JsonSchemas.obj("type", "string",
                    "description", "Path to the file to read (relative or absolute)"),
                "offset", JsonSchemas.obj("type", "integer",
                    "description", "Line number to start reading from (1-indexed)"),
                "limit", JsonSchemas.obj("type", "integer",
                    "description", "Maximum number of lines to read")));
        }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var raw = params.get("path") instanceof String s ? s : "";
            var file = Path.of(raw).isAbsolute() ? Path.of(raw)
                : Path.of(ctx.cwd()).resolve(raw);
            var offset = params.get("offset") instanceof Number n ? n : null;
            var limit = params.get("limit") instanceof Number n ? n : null;
            return read(ctx, file, offset, limit, isBlockedPath(raw));
        }
    }
}
