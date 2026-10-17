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

    /** Create the grep tool. */
    public static AgentTool<GrepInput, GrepDetails> create() {
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
                            "description", "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'"),
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
                return run(params, context);
            }
        };
    }

    /** 对位 pi grep.ts:86-318 的核心流程（纯 Java 引擎）。 */
    private static ToolResult<GrepDetails> run(GrepInput params, ToolContext context)
            throws IOException {
        String searchPath = PathUtils.resolveToolPath(context, params.path().orElse("."));
        Path root = Path.of(searchPath);
        boolean isDirectory;
        if (Files.isDirectory(root)) {
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
        outer:
        for (Path file : candidates) {
            if (isBinarySniff(file)) {
                continue;
            }
            List<String> lines = fileLines(file);
            String display = displayPath(root, file, isDirectory);
            for (int i = 0; i < lines.size(); i++) {
                if (!pattern.matcher(lines.get(i)).find()) {
                    continue;
                }
                matchCount++;
                int lineNumber = i + 1;
                // pi formatBlock（grep.ts:197-215）：窗口 [max(1,N-C), min(len,N+C)]。
                int start = contextValue > 0 ? Math.max(1, lineNumber - contextValue) : lineNumber;
                int end = contextValue > 0 ? Math.min(lines.size(), lineNumber + contextValue) : lineNumber;
                for (int cur = start; cur <= end; cur++) {
                    var lt = TruncationUtils.truncateLine(lines.get(cur - 1),
                        TruncationUtils.GREP_MAX_LINE_LENGTH);
                    if (lt.wasTruncated()) {
                        linesTruncated = true;
                    }
                    outputLines.add(cur == lineNumber
                        ? display + ":" + cur + ": " + lt.text()
                        : display + "-" + cur + "- " + lt.text());
                }
                if (matchCount >= effectiveLimit) {
                    matchLimitReached = true;
                    break outer;
                }
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
        // 有 "/" ⇒ 相对 root 的锚定匹配（与 find 的 fd 语义不同，两工具各写各的）。
        // 不能用 NIO PathMatcher：java 的 ** 不吃零段（'**/*.spec.ts' 漏根下文件），
        // rg 的 **/ 是「零或多段」——走 globToRegex 翻译（评审发现 1）。
        final Pattern globPattern = glob == null ? null : globToRegex(glob);
        final boolean fullPath = glob != null && glob.contains("/");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
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
                return fullPath
                    ? globPattern.matcher(rel(file)).matches()
                    : globPattern.matcher(file.getFileName().toString()).matches();
            }
        });
        return candidates;
    }

    /**
     * rg {@code --glob} 语义翻译（gitignore 式）：{@code **&#47;} ＝ 零或多段、
     * 裸 {@code **} ＝ 跨段任意、{@code *}＝ 段内任意、{@code ?} ＝ 段内单字、
     * {@code [...]} 字符类（{@code !} 取反）、{@code {a,b}} 支选。调用方负责
     * basename 模式（pattern 无 {@code /}）与锚定模式的分派；两侧都全串匹配。
     */
    private static Pattern globToRegex(String glob) {
        var re = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        re.append("(?:[^/]+/)*");   // **/ ⇒ 零或多段（rg 语义）
                        i += 3;
                    } else {
                        re.append(".*");            // 裸 ** ⇒ 跨段任意
                        i += 2;
                    }
                } else {
                    re.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                re.append("[^/]");
                i++;
            } else if (c == '[') {
                int end = glob.indexOf(']', i + 1);
                if (end < 0) {
                    re.append("\\[");
                    i++;
                } else {
                    String cls = glob.substring(i + 1, end);
                    if (cls.startsWith("!")) {
                        cls = "^" + cls.substring(1);
                    }
                    re.append('[').append(cls).append(']');
                    i = end + 1;
                }
            } else if (c == '{') {
                int end = glob.indexOf('}', i + 1);
                if (end < 0) {
                    re.append("\\{");
                    i++;
                } else {
                    String[] alts = glob.substring(i + 1, end).split(",", -1);
                    re.append("(?:");
                    for (int k = 0; k < alts.length; k++) {
                        if (k > 0) {
                            re.append('|');
                        }
                        re.append(Pattern.quote(alts[k]));
                    }
                    re.append(')');
                    i = end + 1;
                }
            } else {
                if ("\\.^$|()+".indexOf(c) >= 0) {
                    re.append('\\');
                }
                re.append(c);
                i++;
            }
        }
        return Pattern.compile(re.append('$').toString());
    }

    /** pi formatPath（grep.ts:137-145）：目录搜索 ⇒ 相对 root（\→/）；单文件 ⇒ basename。 */
    private static String displayPath(Path root, Path file, boolean isDirectory) {
        if (isDirectory) {
            String rel = ToolIgnoreFilter.toPosix(root.relativize(file));
            if (!rel.isEmpty() && !rel.startsWith("..")) {
                return rel;
            }
        }
        return file.getFileName().toString();
    }

    /** pi getFileLines（grep.ts:148-160）：\r\n/\r → \n 归一后按 \n 切（保留尾空段，同 JS split）。 */
    private static List<String> fileLines(Path file) {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return List.of(content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));
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
