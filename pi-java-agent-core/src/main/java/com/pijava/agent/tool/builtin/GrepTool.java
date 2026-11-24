package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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
 * Search file contents for a pattern. Aligned with pi's grep tool
 * （anchor ea448f454，批 3/docs/22 §2）。
 *
 * <p>Schema 七参（pattern/path/glob/ignoreCase/literal/context/limit）、描述、snippet、
 * 输出形（{@code path:N: text}／{@code path-N- text}）、告示序全部逐字。
 * 引擎是纯 Java（裁决①：不移植 rg 外壳与 tools-manager）；方言差见 docs/22 §5.4 D1–D3。</p>
 */
public final class GrepTool {
    /** pi {@code DEFAULT_LIMIT}（grep.ts:42）。 */
    private static final int DEFAULT_LIMIT = 100;
    /** D3：NUL 嗅探预算（首 8 KiB 含 0x00 即按二进制跳过，近似 rg 默认）。 */
    private static final int BINARY_SNIFF_BYTES = 8192;

    private GrepTool() {}

    public record GrepInput(String pattern, Optional<String> path, Optional<String> glob,
            Optional<Boolean> ignoreCase, Optional<Boolean> literal,
            Optional<Integer> context, Optional<Integer> limit) {}

    /** pi {@code GrepToolDetails}（grep.ts:44-48）。 */
    public record GrepDetails(TruncationUtils.TruncationResult truncation,
            Integer matchLimitReached, Boolean linesTruncated) {}

    /**
     * 目录判定接缝（对位 pi {@code GrepOperations}，B287）。搜索本身仍走本地引擎；
     * pi 对远程 grep 的路线是覆写 {@code execute}，不是提供 custom operations
     * （grep.ts 的 GrepOperations 注释）。
     */
    public interface Operations {
        boolean isDirectory(String absolutePath) throws IOException;
    }

    /** Create the grep tool. */
    public static AgentTool<GrepInput, GrepDetails> create() {
        return create(null);
    }

    /** Create the grep tool with an injectable directory-detection seam. */
    public static AgentTool<GrepInput, GrepDetails> create(Operations operations) {
        return new AgentTool<>() {
            @Override public String name() { return "grep"; }
            @Override public String label() { return "grep"; }
            @Override public String description() {
                // pi grep.ts:78 逐字（常量现算）。
                return "Search file contents for a pattern. Returns matching lines with "
                    + "file paths and line numbers. Respects .gitignore. Output is truncated to "
                    + DEFAULT_LIMIT + " matches or "
                    + (TruncationUtils.DEFAULT_MAX_BYTES / 1024)
                    + "KB (whichever is hit first). Long lines are truncated to "
                    + TruncationUtils.GREP_MAX_LINE_LENGTH + " chars.";
            }
            @Override public String promptSnippet() {
                // pi grepToolSystemPromptContribution.snippet（grep.ts:36）。
                return "Search file contents for patterns (respects .gitignore)";
            }
            @Override
            public Map<String, Object> inputSchema() {
                // pi grep.ts:22-32 逐字。
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pattern", Map.of("type", "string",
                            "description", "Search pattern (regex or literal string)"),
                        "path", Map.of("type", "string",
                            "description", "Directory or file to search (default: current directory)"),
                        "glob", Map.of("type", "string",
                            "description", "Filter files by glob pattern relative to path, e.g. "
                                + "'src/**/*.ts'. Without '/', e.g. '*.ts', it matches file names at any depth"),
                        "ignoreCase", Map.of("type", "boolean",
                            "description", "Case-insensitive search (default: false)"),
                        "literal", Map.of("type", "boolean",
                            "description", "Treat pattern as literal string instead of regex (default: false)"),
                        "context", Map.of("type", "number",
                            "description", "Number of lines to show before and after each match (default: 0)"),
                        "limit", Map.of("type", "number",
                            "description", "Maximum number of matches to return (default: 100)")
                    ),
                    "required", List.of("pattern")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

            @Override
            public GrepInput prepareArguments(Map<String, Object> raw) {
                return new GrepInput(
                    (String) raw.get("pattern"),
                    Optional.ofNullable((String) raw.get("path")),
                    Optional.ofNullable((String) raw.get("glob")),
                    raw.get("ignoreCase") instanceof Boolean b ? Optional.of(b) : Optional.empty(),
                    raw.get("literal") instanceof Boolean b ? Optional.of(b) : Optional.empty(),
                    raw.get("context") instanceof Number n ? Optional.of(n.intValue()) : Optional.empty(),
                    raw.get("limit") instanceof Number n ? Optional.of(n.intValue()) : Optional.empty());
            }

            @Override
            public ToolResult<GrepDetails> execute(String toolCallId, GrepInput params,
                    AbortSignal signal, ToolUpdateCallback<GrepDetails> onUpdate,
                    ToolContext context) throws Exception {
                return run(params, context, operations);
            }
        };
    }

    /** 对位 pi grep.ts:86-318 的核心流程（纯 Java 引擎）。 */
    private static ToolResult<GrepDetails> run(GrepInput params, ToolContext context,
            Operations operations) throws IOException {
        String searchPath = PathUtils.resolveToolPath(context, params.path().orElse("."));
        Path root = Path.of(searchPath);
        boolean isDirectory;
        if (operations != null) {
            // pi grep.ts:120-126：custom-ops 只回答 isDirectory，非目录即按文件搜索。
            isDirectory = operations.isDirectory(searchPath);
        } else if (Files.isDirectory(root)) {
            isDirectory = true;
        } else if (Files.isRegularFile(root)) {
            isDirectory = false;
        } else {
            throw new IllegalArgumentException("Path not found: " + searchPath);
        }

        int contextValue = params.context().filter(c -> c > 0).orElse(0);
        int effectiveLimit = Math.max(1, params.limit().orElse(DEFAULT_LIMIT)); // pi grep.ts:136

        int flags = 0;
        if (params.ignoreCase().orElse(false)) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE; // ≙ rg --ignore-case
        }
        if (params.literal().orElse(false)) {
            flags |= Pattern.LITERAL;                                  // ≙ rg --fixed-strings
        }
        Pattern pattern = Pattern.compile(params.pattern(), flags);

        List<Path> candidates = isDirectory
            ? collect(root, params.glob().orElse(null))
            : List.of(root);

        var outputLines = new ArrayList<String>();
        int matchCount = 0;
        boolean matchLimitReached = false;
        boolean linesTruncated = false;
        boolean limitHit = false;
        outer:
        for (Path file : candidates) {
            if (isBinarySniff(file)) {
                continue;
            }
            List<String> lines = fileLines(file);
            String display = displayPath(root, file, isDirectory);
            List<Integer> matches = matchLines(lines, pattern);
            if (limitHit) {
                // Events only arrive for matches now; any remaining match proves the limit cut
                // results off, and the surrounding context events are dropped（grep.ts:243-267）。
                if (!matches.isEmpty()) {
                    matchLimitReached = true;
                    break outer;
                }
                continue;
            }
            // The limit may fall inside this file; only matches up to it are accepted.
            int remaining = effectiveLimit - matchCount;
            int accepted = Math.min(matches.size(), remaining);
            boolean limitInFile = matches.size() > remaining;
            linesTruncated |= emitWindows(outputLines, display, lines, matches, accepted, contextValue);
            matchCount += accepted;
            if (limitInFile) {
                matchLimitReached = true;   // another match already exists past the limit
                break outer;
            }
            if (matchCount >= effectiveLimit) {
                limitHit = true;            // exactly N so far; a later match would prove more
            }
        }
        if (matchCount == 0) {
            return ToolResult.success("No matches found");   // pi grep.ts:257 逐字
        }

        // pi grep.ts:282：纯字节 50KB（maxLines: Number.MAX_SAFE_INTEGER）。
        var truncation = TruncationUtils.truncateHead(String.join("\n", outputLines),
            Integer.MAX_VALUE, TruncationUtils.DEFAULT_MAX_BYTES);
        String output = truncation.content();
        var notices = new ArrayList<String>();
        Integer matchLimitDetail = null;
        Boolean linesTruncatedDetail = null;
        // pi grep.ts:287-303 告示序逐字。
        if (matchLimitReached) {
            notices.add(effectiveLimit + " matches limit reached. Use limit=" + effectiveLimit * 2
                + " for more, or refine pattern");
            matchLimitDetail = effectiveLimit;
        }
        if (truncation.truncated()) {
            notices.add(TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                + " limit reached");
        }
        if (linesTruncated) {
            notices.add("Some lines truncated to " + TruncationUtils.GREP_MAX_LINE_LENGTH
                + " chars. Use read tool to see full lines");
            linesTruncatedDetail = Boolean.TRUE;
        }
        if (!notices.isEmpty()) {
            output += "\n\n[" + String.join(". ", notices) + "]";
        }
        var details = (matchLimitDetail == null && !truncation.truncated()
                && linesTruncatedDetail == null)
            ? null
            : new GrepDetails(truncation.truncated() ? truncation : null,
                matchLimitDetail, linesTruncatedDetail);
        return new ToolResult<>(List.of(new ContentBlock.TextContent(output)),
            details, null, false, List.of());
    }

    /**
     * 候选收集：gitignore（{@link ToolIgnoreFilter}）＋ {@code .git} 硬跳过＋ glob 过滤。
     * rg {@code --hidden} ⇒ dotfiles 照搜。
     */
    private static List<Path> collect(Path root, String glob) throws IOException {
        var ignore = new ToolIgnoreFilter(root);
        var candidates = new ArrayList<Path>();
        // glob 语义按 rg --glob（gitignore 式）：无 "/" ⇒ 匹配 basename；
        // 有 "/" ⇒ 相对 root 的锚定匹配。rg 从不匹配开头的 "./"，所以先剥掉（"!" 取反保留，
        // grep.ts:167）；匹配器与 find 共用 {@link GlobPattern}。
        String effectiveGlob = glob == null ? null
            : glob.replaceAll("^(!?)(?:\\./)+", "$1");
        final GlobPattern globPattern = effectiveGlob == null ? null
            : GlobPattern.compile(effectiveGlob, false);
        final boolean fullPath = effectiveGlob != null && effectiveGlob.contains("/");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path dirName = dir.getFileName();
                if (dirName != null && dirName.toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE; // rg/fd 的 ignore crate 默认不下降 .git
                }
                if (!dir.equals(root) && ignore.isIgnored(rel(dir), true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignore.push(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!ignore.isIgnored(rel(file), false) && matchesGlob(file)) {
                    candidates.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                ignore.pop();
                return FileVisitResult.CONTINUE;
            }

            private String rel(Path p) {
                return ToolIgnoreFilter.toPosix(root.relativize(p));
            }

            private boolean matchesGlob(Path file) {
                if (globPattern == null) {
                    return true;
                }
                if (fullPath) {
                    return globPattern.matches(rel(file));
                }
                Path fileName = file.getFileName();
                return fileName != null && globPattern.matches(fileName.toString());
            }
        });
        return candidates;
    }

    /** 行号（1 起）列表：pattern 命中的行，升序（rg 的 match 事件序）。 */
    private static List<Integer> matchLines(List<String> lines, Pattern pattern) {
        var matches = new ArrayList<Integer>();
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                matches.add(i + 1);
            }
        }
        return matches;
    }

    /**
     * 按 rg 的 context/match 事件语义输出已接受匹配的窗口并集：每个匹配的
     * {@code [m-C, m+C]} 只输出一次（行序升序），接受匹配行用 {@code :}、
     * 其余用 {@code -}。落在最后一条已接受匹配之后的命中行（limit 之后才到的事件）
     * 即使命中窗口也按上下文展示（grep.ts:244-247）。
     *
     * @return 是否有行被 {@link TruncationUtils#truncateLine} 截断
     */
    private static boolean emitWindows(List<String> output, String display, List<String> lines,
            List<Integer> matches, int accepted, int context) {
        if (accepted == 0) {
            return false;
        }
        int lastAccepted = matches.get(accepted - 1);
        var matchFlag = new boolean[lines.size() + 1];
        for (int line : matches) {
            matchFlag[line] = true;
        }
        boolean truncated = false;
        int emittedThrough = 0;
        for (int k = 0; k < accepted; k++) {
            int matchLine = matches.get(k);
            int start = context > 0 ? Math.max(1, matchLine - context) : matchLine;
            int end = context > 0 ? Math.min(lines.size(), matchLine + context) : matchLine;
            for (int cur = Math.max(start, emittedThrough + 1); cur <= end; cur++) {
                truncated |= appendLine(output, display, cur, lines.get(cur - 1),
                    matchFlag[cur] && cur <= lastAccepted);
            }
            emittedThrough = Math.max(emittedThrough, end);
        }
        return truncated;
    }

    /** 一行输出：命中 ⇒ {@code path:N: text}，上下文 ⇒ {@code path-N- text}。 */
    private static boolean appendLine(List<String> output, String display, int lineNumber,
            String rawText, boolean isMatch) {
        var lt = TruncationUtils.truncateLine(stripTrailingCr(rawText),
            TruncationUtils.GREP_MAX_LINE_LENGTH);
        output.add(isMatch
            ? display + ":" + lineNumber + ": " + lt.text()
            : display + "-" + lineNumber + "- " + lt.text());
        return lt.wasTruncated();
    }

    /**
     * pi 的 {@code lineText.replace(/\r?\n?$/, "")} 对位：按 {@code \n} 切分后只剥一个
     * 行尾 {@code \r}；孤立的 {@code \r} 留在行内（它只是内容，不是换行）。
     */
    private static String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** pi formatPath（grep.ts:137-145）：目录搜索 ⇒ 相对 root（\→/）；单文件 ⇒ basename。 */
    private static String displayPath(Path root, Path file, boolean isDirectory) {
        if (isDirectory) {
            String rel = ToolIgnoreFilter.toPosix(root.relativize(file));
            if (!rel.isEmpty() && !rel.startsWith("..")) {
                return rel;
            }
        }
        Path fileName = file.getFileName();
        return fileName == null ? file.toString() : fileName.toString();
    }

    /**
     * pi getFileLines（grep.ts:148-160）：ripgrep 只按 {@code \n} 切行（保留尾空段，
     * 同 JS {@code split}）；行尾的 {@code \r} 在输出时再剥（见 {@link #stripTrailingCr}）。
     */
    private static List<String> fileLines(Path file) {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return List.of(content.split("\n", -1));
        } catch (IOException e) {
            return List.of();   // pi: catch ⇒ lines = []
        }
    }

    /** D3：首 {@value #BINARY_SNIFF_BYTES} 字节含 0x00 即按二进制跳过。 */
    private static boolean isBinarySniff(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[BINARY_SNIFF_BYTES];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
