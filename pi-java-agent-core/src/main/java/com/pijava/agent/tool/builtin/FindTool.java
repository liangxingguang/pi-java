package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.PathUtils;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.TruncationUtils;
import com.pijava.agent.tool.ignore.ToolIgnoreFilter;
import com.pijava.ai.message.ContentBlock;

/**
 * Search for files by glob pattern. Aligned with pi's find tool
 * （anchor ea448f454，批 3/docs/22 §3；由旧 {@code GlobTool} 改名＋参数集换血）。
 *
 * <p>Schema 三参（pattern/path/limit）、描述、snippet、相对路径输出、告示逐字。
 * fd 语义：无 {@code /} 的 pattern 匹配 basename；含 {@code /} ⇒ full-path 模式
 * ＋ {@code **&#47;} 前缀适配（find.ts:201-213），对<b>绝对候选路径</b>匹配
 * （java {@code **} 不匹配零段，相对路径会漏根下直接命中——fd 本就匹配绝对路径）。
 * 目录与文件都入选（fd 无 --type）。引擎是纯 Java（裁决①）；方言差见 docs/22 §5.4 D5。</p>
 */
public final class FindTool {
    /** pi {@code DEFAULT_LIMIT}（find.ts:41）。 */
    private static final int DEFAULT_LIMIT = 1000;

    private FindTool() {}

    public record FindInput(String pattern, Optional<String> path, Optional<Integer> limit) {}

    /** pi {@code FindToolDetails}（find.ts:43-46）。 */
    public record FindDetails(TruncationUtils.TruncationResult truncation,
            Integer resultLimitReached) {}

    /** Create the find tool. */
    public static AgentTool<FindInput, FindDetails> create() {
        return new AgentTool<>() {
            @Override public String name() { return "find"; }
            @Override public String label() { return "find"; }
            @Override public String description() {
                // pi find.ts:78 逐字（常量现算）。
                return "Search for files by glob pattern. Returns matching file paths "
                    + "relative to the search directory. Respects .gitignore. "
                    + "Output is truncated to " + DEFAULT_LIMIT + " results or "
                    + (TruncationUtils.DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first).";
            }
            @Override public String promptSnippet() {
                // pi findToolSystemPromptContribution.snippet（find.ts:35）。
                return "Find files by glob pattern (respects .gitignore)";
            }
            @Override
            public Map<String, Object> inputSchema() {
                // pi find.ts:26-32 逐字。
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pattern", Map.of("type", "string",
                            "description", "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'"),
                        "path", Map.of("type", "string",
                            "description", "Directory to search in (default: current directory)"),
                        "limit", Map.of("type", "number",
                            "description", "Maximum number of results (default: 1000)")
                    ),
                    "required", List.of("pattern")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

            @Override
            public FindInput prepareArguments(Map<String, Object> raw) {
                return new FindInput(
                    (String) raw.get("pattern"),
                    Optional.ofNullable((String) raw.get("path")),
                    raw.get("limit") instanceof Number n ? Optional.of(n.intValue()) : Optional.empty());
            }

            @Override
            public ToolResult<FindDetails> execute(String toolCallId, FindInput params,
                    AbortSignal signal, ToolUpdateCallback<FindDetails> onUpdate,
                    ToolContext context) throws Exception {
                return run(params, context);
            }
        };
    }

    /** 对位 pi find.ts:88-310 的核心流程（纯 Java 引擎，fd 分支形状）。 */
    private static ToolResult<FindDetails> run(FindInput params, ToolContext context)
            throws IOException {
        String searchPath = PathUtils.resolveToolPath(context, params.path().orElse("."));
        Path root = Path.of(searchPath);
        // 错文采 pi custom-ops 分支形（find.ts:118；fd 分支报 fd 自身 stderr，不可逐字——D5）。
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Path not found: " + searchPath);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + searchPath);
        }
        // pi find.ts:112：limit ?? DEFAULT_LIMIT，无 max(1,…) —— 0/负值 ⇒ 空结果，照抄。
        int effectiveLimit = params.limit().orElse(DEFAULT_LIMIT);

        // fd --glob 语义（find.ts:204-213）：默认匹配 basename；含 "/" ⇒ --full-path
        // 且未锚定时补 "**/" 前缀，对绝对候选路径匹配。
        String p = params.pattern();
        boolean fullPath = p.contains("/");
        String eff = p;
        if (fullPath && !p.startsWith("/") && !p.startsWith("**/") && !p.equals("**")) {
            eff = "**/" + p;
        }
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + eff);

        var ignore = new ToolIgnoreFilter(root);
        var results = new ArrayList<String>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path dirName = dir.getFileName();
                    if (dirName != null && dirName.toString().equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root)) {
                        if (ignore.isIgnored(rel(dir), true)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // fd 无 --type：目录也入选（find.ts:201 注释；输出不带尾分隔符）。
                        if (matches(dir)) {
                            add(rel(dir));
                        }
                    }
                    ignore.push(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!ignore.isIgnored(rel(file), false) && matches(file)) {
                        add(rel(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    ignore.pop();
                    return FileVisitResult.CONTINUE;
                }

                private void add(String relPath) {
                    if (results.size() >= effectiveLimit) {
                        throw new LimitReached();   // ≙ fd --max-results 提前终止
                    }
                    results.add(relPath);
                }

                private boolean matches(Path candidate) {
                    return fullPath
                        ? matcher.matches(candidate.toAbsolutePath().normalize())
                        : matcher.matches(candidate.getFileName());
                }

                private String rel(Path candidate) {
                    return ToolIgnoreFilter.toPosix(root.relativize(candidate));
                }
            });
        } catch (LimitReached e) {
            // 达 limit 提前终止（fd --max-results 对位），结果即终局。
        }

        // pi find.ts:275：relativized.length >= effectiveLimit。
        boolean resultLimitReached = results.size() >= effectiveLimit;
        if (results.isEmpty()) {
            return ToolResult.success("No files found matching pattern"); // find.ts:261 逐字
        }

        // pi find.ts:276-299：纯字节 50KB＋notices 逐字序。
        var truncation = TruncationUtils.truncateHead(String.join("\n", results),
            Integer.MAX_VALUE, TruncationUtils.DEFAULT_MAX_BYTES);
        String output = truncation.content();
        var notices = new ArrayList<String>();
        Integer limitDetail = null;
        if (resultLimitReached) {
            notices.add(effectiveLimit + " results limit reached. Use limit=" + effectiveLimit * 2
                + " for more, or refine pattern");
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
            : new FindDetails(truncation.truncated() ? truncation : null, limitDetail);
        return new ToolResult<>(List.of(new ContentBlock.TextContent(output)),
            details, null, false, List.of());
    }

    /** 达 limit 提前终止遍历的控制流信号（fd --max-results 对位）。 */
    private static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
