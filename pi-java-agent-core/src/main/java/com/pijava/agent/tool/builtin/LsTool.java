package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.FileInfo;
import com.pijava.agent.tool.FileSystem;
import com.pijava.agent.tool.PathUtils;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.TruncationUtils;
import com.pijava.ai.message.ContentBlock;

/**
 * List directory contents. Aligned with pi's ls tool
 * （anchor ea448f454，批 3/docs/22 §4）。
 *
 * <p>Schema 两参（path/limit；{@code recursive} 已删）、描述、snippet、裸名输出
 * （{@code /} 目录后缀，无 {@code [DIR]/[FILE]} 前缀与 size 列）、大小写不敏感排序、
 * 告示与三错误文本逐字。排序取 {@code toLowerCase(ROOT)} 码点序——pi 的
 * {@code localeCompare} 差见 docs/22 §5.4 D4（ASCII 全等）。</p>
 */
public final class LsTool {
    /** pi {@code DEFAULT_LIMIT}（ls.ts:25）。 */
    private static final int DEFAULT_LIMIT = 500;

    private LsTool() {}

    public record LsInput(Optional<String> path, Optional<Integer> limit) {}

    /** pi {@code LsToolDetails}（ls.ts:28-31）。 */
    public record LsDetails(TruncationUtils.TruncationResult truncation,
            Integer entryLimitReached) {}

    /**
     * 目录读取接缝（对位 pi {@code LsOperations}，B287）：默认实现走
     * {@link ToolContext#fs()}；远程后端可注入自己的实现。
     */
    public interface Operations {
        boolean exists(String absolutePath) throws IOException;
        boolean isDirectory(String absolutePath) throws IOException;
        List<String> listNames(String absolutePath) throws IOException;

        static Operations from(ToolContext context) {
            FileSystem fs = context.fs();
            return new Operations() {
                @Override public boolean exists(String absolutePath) {
                    try {
                        fs.fileInfo(absolutePath);
                        return true;
                    } catch (IOException e) {
                        return false;
                    }
                }
                @Override public boolean isDirectory(String absolutePath) throws IOException {
                    return "dir".equals(fs.fileInfo(absolutePath).kind());
                }
                @Override public List<String> listNames(String absolutePath) throws IOException {
                    var names = new ArrayList<String>();
                    for (FileInfo info : fs.listDir(absolutePath, false)) {
                        Path name = Path.of(info.path()).getFileName();
                        if (name != null) {
                            names.add(name.toString());
                        }
                    }
                    return names;
                }
            };
        }
    }

    /** Create the ls tool. */
    public static AgentTool<LsInput, LsDetails> create() {
        return create(null);
    }

    /** Create the ls tool with an injectable filesystem seam. */
    public static AgentTool<LsInput, LsDetails> create(Operations operations) {
        return new AgentTool<>() {
            @Override public String name() { return "ls"; }
            @Override public String label() { return "ls"; }
            @Override public String description() {
                // pi ls.ts:62 逐字（常量现算）。
                return "List directory contents. Returns entries sorted alphabetically, "
                    + "with '/' suffix for directories. Includes dotfiles. "
                    + "Output is truncated to " + DEFAULT_LIMIT + " entries or "
                    + (TruncationUtils.DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first).";
            }
            @Override public String promptSnippet() {
                // pi lsToolSystemPromptContribution.snippet（ls.ts:17-20）。
                return "List directory contents";
            }
            @Override
            public Map<String, Object> inputSchema() {
                // pi ls.ts:11-15 逐字。
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string",
                            "description", "Directory to list (default: current directory)"),
                        "limit", Map.of("type", "number",
                            "description", "Maximum number of entries to return (default: 500)")
                    ),
                    "required", List.of()
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

            @Override
            public LsInput prepareArguments(Map<String, Object> raw) {
                return new LsInput(
                    Optional.ofNullable((String) raw.get("path")),
                    raw.get("limit") instanceof Number n ? Optional.of(n.intValue()) : Optional.empty());
            }

            @Override
            public ToolResult<LsDetails> execute(String toolCallId, LsInput params,
                    AbortSignal signal, ToolUpdateCallback<LsDetails> onUpdate,
                    ToolContext context) throws Exception {
                return run(params, context, operations);
            }
        };
    }

    /** 对位 pi ls.ts:86-172。 */
    private static ToolResult<LsDetails> run(LsInput params, ToolContext context,
            Operations operations) throws IOException {
        String dirPath = PathUtils.resolveToolPath(context, params.path().orElse("."));
        Operations ops = operations != null ? operations : Operations.from(context);
        // pi ls.ts:84：limit ?? DEFAULT_LIMIT，无 max(1,…)。
        int effectiveLimit = params.limit().orElse(DEFAULT_LIMIT);
        if (!ops.exists(dirPath)) {
            throw new IllegalArgumentException("Path not found: " + dirPath);
        }
        if (!ops.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Not a directory: " + dirPath);
        }
        List<String> entries;
        try {
            entries = ops.listNames(dirPath);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read directory: " + e.getMessage());
        }
        // pi ls.ts:109：toLowerCase 后排序；java 取 toLowerCase(ROOT) 码点序（D4）。
        entries = new ArrayList<>(entries);
        entries.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));

        var results = new ArrayList<String>();
        boolean entryLimitReached = false;
        for (String entry : entries) {
            if (results.size() >= effectiveLimit) {
                entryLimitReached = true;
                break;
            }
            try {
                if (ops.isDirectory(Path.of(dirPath).resolve(entry).toString())) {
                    entry += "/";
                }
            } catch (IOException | RuntimeException e) {
                continue;   // pi ls.ts:124-128：stat 失败跳过
            }
            results.add(entry);
        }
        if (results.isEmpty()) {
            return ToolResult.success("(empty directory)");   // pi ls.ts:135 逐字
        }

        // pi ls.ts:139-156：纯字节 50KB＋notices 逐字序。
        var truncation = TruncationUtils.truncateHead(String.join("\n", results),
            Integer.MAX_VALUE, TruncationUtils.DEFAULT_MAX_BYTES);
        String output = truncation.content();
        var notices = new ArrayList<String>();
        Integer limitDetail = null;
        if (entryLimitReached) {
            notices.add(effectiveLimit + " entries limit reached. Use limit="
                + effectiveLimit * 2 + " for more");
            limitDetail = effectiveLimit;
        }
        if (truncation.truncated()) {
            notices.add(TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                + " limit reached");
        }
        if (!notices.isEmpty()) {
            output += "\n\n[" + String.join(". ", notices) + "]";
        }
        var details = (limitDetail == null && !truncation.truncated())
            ? null
            : new LsDetails(truncation.truncated() ? truncation : null, limitDetail);
        return new ToolResult<>(List.of(new ContentBlock.TextContent(output)),
            details, null, false, List.of());
    }
}
