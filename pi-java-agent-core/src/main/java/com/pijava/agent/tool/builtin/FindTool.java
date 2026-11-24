package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.GlobPattern;
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
 * fd 语义（find.ts:209-252）：Windows 先归一 {@code \} 并把开头的 {@code ./} 剥掉；
 * 无 {@code /} 的 pattern 匹配 basename，大小写按 smart case；含 {@code /} 的
 * <b>相对</b> pattern 锚定搜索根（{@code src/*.ts} 不吃 {@code pkg/src/b.ts}），
 * 绝对 pattern 匹配绝对路径。目录与文件都入选（fd 无 --type）。
 * 引擎是纯 Java（裁决①）；方言差见 docs/22 §5.4 D5。</p>
 */
public final class FindTool {
    /** pi {@code DEFAULT_LIMIT}（find.ts:41）。 */
    private static final int DEFAULT_LIMIT = 1000;

    private FindTool() {}

    public record FindInput(String pattern, Optional<String> path, Optional<Integer> limit) {}

    /** pi {@code FindToolDetails}（find.ts:43-46）。 */
    public record FindDetails(TruncationUtils.TruncationResult truncation,
            Integer resultLimitReached) {}

    /**
     * 文件查找接缝（对位 pi {@code FindOperations}，B287）：默认实现是本地遍历；
     * 远程后端注入 {@link #glob} 即可替换文件后端。
     */
    public interface Operations {
        /** pi 的 custom-ops 分支固定把这两条忽略规则交给实现（find.ts:118）。 */
        List<String> DEFAULT_IGNORE = List.of("**/node_modules/**", "**/.git/**");

        boolean exists(String absolutePath) throws IOException;
        boolean isDirectory(String absolutePath) throws IOException;

        /** 返回匹配项（绝对路径）；最多 {@code maxResults} 条。 */
        List<String> glob(String pattern, String absoluteRoot, List<String> ignore,
                int maxResults) throws IOException;
    }

    /** Create the find tool. */
    public static AgentTool<FindInput, FindDetails> create() {
        return create(null);
    }

    /** Create the find tool with an injectable filesystem seam. */
    public static AgentTool<FindInput, FindDetails> create(Operations operations) {
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
                            "description", "Glob pattern relative to the search directory, e.g. "
                                + "'src/**/*.ts'. Without '/', e.g. '*.ts', it matches file names at any depth"),
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
                return run(params, context, operations);
            }
        };
    }

    /** 对位 pi find.ts:88-310 的核心流程（纯 Java 引擎，fd 分支形状）。 */
    private static ToolResult<FindDetails> run(FindInput params, ToolContext context,
            Operations operations) throws IOException {
        String searchPath = PathUtils.resolveToolPath(context, params.path().orElse("."));
        Path root = Path.of(searchPath);
        // 错文采 pi custom-ops 分支形（find.ts:118；fd 分支报 fd 自身 stderr，不可逐字——D5）。
        boolean exists = operations != null ? operations.exists(searchPath) : Files.exists(root);
        if (!exists) {
            throw new IllegalArgumentException("Path not found: " + searchPath);
        }
        boolean isDirectory = operations != null
            ? operations.isDirectory(searchPath) : Files.isDirectory(root);
        if (!isDirectory) {
            throw new IllegalArgumentException("Not a directory: " + searchPath);
        }
        // fd treats --max-results 0 as unlimited, so a limit below 1 means 1（find.ts:114）。
        int effectiveLimit = Math.max(1, params.limit().orElse(DEFAULT_LIMIT));

        // fd 语义（find.ts:209-252）：pattern 预处理 + smart case + 锚定。
        String pattern = normalizePattern(params.pattern(), File.separatorChar == '\\');
        boolean isPathPattern = pattern.contains("/");
        boolean ignoreCase = pattern.equals(pattern.toLowerCase(Locale.ROOT));
        boolean isRelativePathPattern = isPathPattern && !isAbsolutePattern(pattern);
        final GlobPattern matcher = GlobPattern.compile(pattern, ignoreCase);

        final List<String> results;
        final boolean resultLimitReached;
        if (operations != null) {
            var matches = operations.glob(params.pattern(), searchPath,
                Operations.DEFAULT_IGNORE, effectiveLimit);
            var relResults = new ArrayList<String>();
            for (String match : matches) {
                if (relResults.size() >= effectiveLimit) {
                    break;
                }
                String rel = relativize(searchPath, match);
                if (rel.startsWith("..")) {
                    continue;
                }
                relResults.add(rel);
            }
            results = relResults;
            // pi find.ts:126-132：custom-ops 分支饱和即视为触限。
            resultLimitReached = results.size() >= effectiveLimit;
        } else {
            final var ignore = new ToolIgnoreFilter(root);
            final var localResults = new ArrayList<String>();

            class Walker extends SimpleFileVisitor<Path> {
                private boolean limitReached;

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
                        // fd 无 --type：目录也入选（输出不带尾分隔符）。
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
                    // One result past the limit tells "exactly N" apart from "more than N"（#7121）。
                    if (localResults.size() >= effectiveLimit) {
                        limitReached = true;
                        throw new LimitReached();
                    }
                    localResults.add(relPath);
                }

                private boolean matches(Path candidate) {
                    if (!isPathPattern) {
                        Path name = candidate.getFileName();
                        return name != null && matcher.matches(name.toString());
                    }
                    String target = isRelativePathPattern
                        ? rel(candidate)
                        : ToolIgnoreFilter.toPosix(candidate.toAbsolutePath().normalize());
                    return matcher.matches(target);
                }

                private String rel(Path candidate) {
                    return ToolIgnoreFilter.toPosix(root.relativize(candidate));
                }
            }

            var walker = new Walker();
            try {
                Files.walkFileTree(root, walker);
            } catch (LimitReached e) {
                // 达 limit 提前终止（fd --max-results 对位），结果即终局。
            }
            results = localResults;
            resultLimitReached = walker.limitReached;
        }
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

    /** custom-ops 分支的 {@code relative(searchPath, match)}：按字符串前缀剥离，避免本地路径解析。 */
    private static String relativize(String root, String match) {
        String rel = match;
        if (rel.equals(root)) {
            rel = "";
        } else if (rel.startsWith(root + "/") || rel.startsWith(root + "\\")) {
            rel = rel.substring(root.length() + 1);
        }
        return rel.replace('\\', '/');
    }

    /**
     * fd 的 pattern 预处理（find.ts:209-212）：Windows 上 {@code \} 是路径分隔符，先归一成
     * {@code /}；fd 的 full path 不含 {@code ./}，所以开头的一或多段 {@code ./} 剥掉。
     * 包内可见以便夹具在不依赖平台时验证两个分支。
     */
    static String normalizePattern(String pattern, boolean windows) {
        String p = windows ? pattern.replace('\\', '/') : pattern;
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }

    /** {@code path.isAbsolute} 的 glob 版：POSIX 根或 {@code C:/} 盘符。 */
    private static boolean isAbsolutePattern(String pattern) {
        return pattern.startsWith("/")
            || (pattern.length() >= 3 && Character.isLetter(pattern.charAt(0))
                && pattern.charAt(1) == ':' && pattern.charAt(2) == '/');
    }

    /** 达 limit 提前终止遍历的控制流信号（fd --max-results 对位）。 */
    private static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
