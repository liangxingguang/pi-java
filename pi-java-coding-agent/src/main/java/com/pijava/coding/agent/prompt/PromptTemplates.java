package com.pijava.coding.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.pijava.agent.tool.FileInfo;
import com.pijava.agent.tool.FileSystem;
import com.pijava.coding.agent.core.source.SourceInfo;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * User-defined prompt templates（pi {@code core/prompt-templates.ts:222-297}）。
 *
 * <p>加载目录/文件中的 {@code .md} 模板（YAML frontmatter 的 {@code description}/
 * {@code argument-hint} + markdown 正文），并提供命令行参数替换：{@code $1}、
 * {@code $@}、{@code $ARGUMENTS}、{@code ${@:N}}、{@code ${@:N:L}}、
 * {@code ${N:-default}}、{@code ${@:-default}}、{@code ${ARGUMENTS:-default}}。</p>
 */
public final class PromptTemplates {

    /**
     * 一个已加载的模板。
     *
     * <p>{@code argumentHint} 对位 pi {@code PromptTemplate.argumentHint?}
     * （prompt-templates.ts:19，读 {@code argument-hint} frontmatter :144）；
     * {@code filePath} 为模板文件路径。两者 {@code null} ≙ pi 的缺席。</p>
     *
     * <p>B247：{@code sourceInfo} 对位 pi {@code PromptTemplate.sourceInfo}
     * （prompt-templates.ts:16），由加载期按来源目录打标（:235-261）；
     * 兼容构造/手造实例为 {@code null} ≙ pi 的缺席。</p>
     */
    public record PromptTemplate(String name, String description, String content,
                                 String argumentHint, String filePath,
                                 SourceInfo sourceInfo) {

        /** 兼容构造：无 {@code argumentHint}/{@code filePath}/{@code sourceInfo}。 */
        public PromptTemplate(String name, String description, String content) {
            this(name, description, content, null, null, null);
        }
    }

    /** 加载告警（对齐 pi {@code PromptTemplateDiagnostic}）。 */
    public record Diagnostic(String code, String message, String path) {
    }

    /** 加载结果：模板 + 告警。 */
    public record LoadResult(List<PromptTemplate> templates, List<Diagnostic> diagnostics) {
    }

    private PromptTemplates() {
    }

    // ── 加载 ────────────────────────────────────────────────────────────

    /**
     * 从一个或多个路径加载模板：目录读取直接 {@code .md} 子文件（非递归），
     * 文件读取显式 {@code .md}；**缺失路径静默跳过**（pi
     * {@code prompt-templates.ts:271-273}），非 markdown 跳过，读/解析失败返回告警。
     */
    public static LoadResult load(FileSystem fs, List<String> paths) {
        var templates = new ArrayList<PromptTemplate>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (var path : paths) {
            FileInfo info;
            try {
                info = fs.fileInfo(path);
            } catch (IOException e) {
                continue; // pi :271-273：显式路径不存在 ⇒ 静默跳过（无诊断）
            }
            // B247（pi :257-261）：显式路径 scope 缺省落 temporary；baseDir
            // 目录入参⇒自身、文件入参⇒父目录（pi :259 statSync 分支）。
            if ("dir".equals(info.kind())) {
                loadFromDir(fs, info.path(), templates, diagnostics,
                    SourceInfo.Scope.TEMPORARY, info.path());
            } else if ("file".equals(info.kind()) && info.path().endsWith(".md")) {
                loadFromFile(fs, info.path(), templates, diagnostics,
                    SourceInfo.Scope.TEMPORARY, parentDir(info.path()));
            }
        }
        return new LoadResult(templates, diagnostics);
    }

    /**
     * pi {@code includeDefaults} 分支（:262-265）＋ {@code getSourceInfo} 打标
     * （:247-256）：默认目录两路 —— {@code agentDir/prompts}（scope=user）与
     * {@code cwd/.pi-java/prompts}（scope=project，品牌分叉：pi 是 {@code .pi}，
     * 窄差登记 docs/09 §14），baseDir 各为所属 prompts 目录。缺失目录静默
     * （零诊断，pi :164-166 existsSync 短路）。
     */
    public static LoadResult loadDefaults(FileSystem fs, String agentDir, String cwd) {
        var sep = java.io.File.separator;
        var globalDir = agentDir + sep + "prompts";
        var projectDir = cwd + sep + ".pi-java" + sep + "prompts";
        var templates = new ArrayList<PromptTemplate>();
        var diagnostics = new ArrayList<Diagnostic>();
        loadFromDir(fs, globalDir, templates, diagnostics, SourceInfo.Scope.USER, globalDir);
        loadFromDir(fs, projectDir, templates, diagnostics, SourceInfo.Scope.PROJECT, projectDir);
        return new LoadResult(templates, diagnostics);
    }

    private static void loadFromDir(FileSystem fs, String dir,
            List<PromptTemplate> templates, List<Diagnostic> diagnostics,
            SourceInfo.Scope scope, String baseDir) {
        try {
            fs.fileInfo(dir);
        } catch (IOException e) {
            return; // pi :164-166：目录不存在 ⇒ 静默短路（零诊断）
        }
        List<FileInfo> entries;
        try {
            entries = fs.listDir(dir, false);
        } catch (IOException e) {
            diagnostics.add(new Diagnostic("list_failed", e.getMessage(), dir));
            return;
        }
        entries.sort(Comparator.comparing(FileInfo::path));
        for (var entry : entries) {
            if (!"file".equals(entry.kind()) || !entry.path().endsWith(".md")) {
                continue;
            }
            loadFromFile(fs, entry.path(), templates, diagnostics, scope, baseDir);
        }
    }

    private static void loadFromFile(FileSystem fs, String filePath,
            List<PromptTemplate> templates, List<Diagnostic> diagnostics,
            SourceInfo.Scope scope, String baseDir) {
        String raw;
        try {
            raw = new String(fs.readBinary(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            diagnostics.add(new Diagnostic("read_failed", e.getMessage(), filePath));
            return;
        }
        var parsed = parseFrontmatter(raw);
        String fileName = baseNameWithoutMd(filePath);
        String description = parsed.frontmatter().get("description") instanceof String d ? d : "";
        if (description.isBlank()) {
            var firstLine = parsed.body().lines().filter(line -> !line.isBlank()).findFirst().orElse("");
            description = firstLine.length() > 60 ? firstLine.substring(0, 60) + "..." : firstLine;
        }
        // pi :144：argument-hint frontmatter ⇒ argumentHint。
        String argumentHint =
            parsed.frontmatter().get("argument-hint") instanceof String h ? h : null;
        // B247（pi :235-261 经 createSyntheticSourceInfo，source-info.ts:33-46）：
        // source 恒 "local"、origin 恒 top-level，scope/baseDir 由调用方按来源定。
        templates.add(new PromptTemplate(fileName, description, parsed.body(),
            argumentHint, filePath,
            new SourceInfo(filePath, "local", scope, SourceInfo.Origin.TOP_LEVEL, baseDir)));
    }

    /** 父目录（pi {@code dirname}）；无分隔符时返回空串。 */
    private static String parentDir(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    private static String baseNameWithoutMd(String path) {
        String base = path;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        return base.endsWith(".md") ? base.substring(0, base.length() - 3) : base;
    }

    // ── Frontmatter ─────────────────────────────────────────────────────

    record Parsed(Map<String, Object> frontmatter, String body) {
    }

    /** 解析 YAML frontmatter（pi {@code parseFrontmatter}：无 {@code ---} 或未闭合时整文件为正文）。 */
    static Parsed parseFrontmatter(String content) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith("---")) {
            return new Parsed(Map.of(), normalized);
        }
        int endIndex = normalized.indexOf("\n---", 3);
        if (endIndex == -1) {
            return new Parsed(Map.of(), normalized);
        }
        // Empty frontmatter ("---\n---\n…") has endIndex == 3; JS slice clamps,
        // Java substring must guard.
        String yamlString = endIndex >= 4 ? normalized.substring(4, endIndex) : "";
        String body = normalized.substring(endIndex + 4).trim();
        return new Parsed(parseYaml(yamlString), body);
    }

    @SuppressWarnings("unchecked") // snakeyaml load returns Object; we require a mapping
    private static Map<String, Object> parseYaml(String yamlText) {
        if (yamlText.isBlank()) {
            return Map.of();
        }
        try {
            var loader = new LoaderOptions();
            loader.setAllowDuplicateKeys(false);
            Object value = new Yaml(loader).load(yamlText);
            return value instanceof Map ? (Map<String, Object>) value : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── 参数解析 / 替换 ─────────────────────────────────────────────────

    /** 用 shell 式单/双引号解析参数字符串（pi {@code parseCommandArgs}）。 */
    public static List<String> parseCommandArgs(String argsString) {
        var args = new ArrayList<String>();
        var current = new StringBuilder();
        Character inQuote = null;
        for (int i = 0; i < argsString.length(); i++) {
            char c = argsString.charAt(i);
            if (inQuote != null) {
                if (c == inQuote) {
                    inQuote = null;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = c;
            } else if (c == ' ' || c == '\t') {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    /**
     * pi {@code substituteArgs}（prompt-templates.ts:71-109）逐字对位：**单正则五支、
     * 单 pass**（不递归替换）：
     *
     * <pre>
     * /\$\{(\d+|ARGUMENTS|@):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)/g
     * </pre>
     *
     * <p>支序：① {@code ${N:-d}}/{@code ${@:-d}}/{@code ${ARGUMENTS:-d}}（default 判空是
     * JS 真值 —— <b>空串也落 default</b>，{@code $N} 越界 ≙ undefined ⇒ default）；
     * ② {@code ${@:N[:L]}} 切片（0 按 1 计）；③ {@code $N}/{@code $@}/{@code $ARGUMENTS}
     * （{@code $N} 越界 ⇒ 空串）。</p>
     */
    private static final Pattern ARGS_PATTERN = Pattern.compile(
        "\\$\\{(\\d+|ARGUMENTS|@):-([^}]*)}|\\$\\{@:(\\d+)(?::(\\d+))?}|\\$(ARGUMENTS|@|\\d+)");

    /** 见 {@link #ARGS_PATTERN}。 */
    public static String substituteArgs(String content, List<String> args) {
        String allArgs = String.join(" ", args);
        return replaceAll(ARGS_PATTERN, content, m -> {
            String defaultTarget = m.group(1);
            if (defaultTarget != null) {
                // pi :412-415：@/ARGUMENTS ⇒ allArgs；否则 args[N-1]（越界 ≙ undefined）。
                String value = defaultTarget.equals("@") || defaultTarget.equals("ARGUMENTS")
                    ? allArgs
                    : indexed(args, Integer.parseInt(defaultTarget) - 1);
                // JS `value ? value : defaultValue`：null 与空串都落 default。
                return value != null && !value.isEmpty() ? value : m.group(2);
            }
            if (m.group(3) != null) {
                // ${@:N[:L]}：0 按 1 计（JS Math.max(0, N-1)）。
                int start = Math.max(0, Integer.parseInt(m.group(3)) - 1);
                var stream = args.stream().skip(start);
                if (m.group(4) != null) {
                    stream = stream.limit(Integer.parseInt(m.group(4)));
                }
                return stream.reduce((a, b) -> a + " " + b).orElse("");
            }
            String simple = m.group(5);
            if (simple.equals("ARGUMENTS") || simple.equals("@")) {
                return allArgs;
            }
            String indexed = indexed(args, Integer.parseInt(simple) - 1);
            return indexed != null ? indexed : ""; // pi :420 `args[index] ?? ""`
        });
    }

    /** {@code args[i]}，越界 ⇒ {@code null}（≙ JS undefined）。 */
    private static String indexed(List<String> args, int index) {
        return index >= 0 && index < args.size() ? args.get(index) : null;
    }

    /** {@code Matcher.appendReplacement}-based substitution with escaped replacements. */
    private static String replaceAll(Pattern pattern, String input,
            java.util.function.Function<java.util.regex.Matcher, String> replacer) {
        var matcher = pattern.matcher(input);
        var sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 用位置参数格式化一次模板调用（pi {@code formatPromptTemplateInvocation}）。 */
    public static String formatPromptTemplateInvocation(PromptTemplate template, List<String> args) {
        return substituteArgs(template.content(), args);
    }
}
