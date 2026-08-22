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

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * User-defined prompt templates（pi {@code harness/prompt-templates.ts}）。
 *
 * <p>加载目录/文件中的 {@code .md} 模板（YAML frontmatter 的 {@code description}/
 * {@code argument-hint} + markdown 正文），并提供命令行参数替换：{@code $1}、
 * {@code $@}、{@code $ARGUMENTS}、{@code ${@:N}}、{@code ${@:N:L}}。</p>
 */
public final class PromptTemplates {

    /** 一个已加载的模板：名称（文件名去 .md）、描述、正文。 */
    public record PromptTemplate(String name, String description, String content) {
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
     * 文件读取显式 {@code .md}；缺失路径与非 markdown 跳过，读/解析失败返回告警。
     */
    public static LoadResult load(FileSystem fs, List<String> paths) {
        var templates = new ArrayList<PromptTemplate>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (var path : paths) {
            FileInfo info;
            try {
                info = fs.fileInfo(path);
            } catch (IOException e) {
                diagnostics.add(new Diagnostic("file_info_failed", e.getMessage(), path));
                continue;
            }
            if ("dir".equals(info.kind())) {
                loadFromDir(fs, info.path(), templates, diagnostics);
            } else if ("file".equals(info.kind()) && info.path().endsWith(".md")) {
                loadFromFile(fs, info.path(), templates, diagnostics);
            }
        }
        return new LoadResult(templates, diagnostics);
    }

    private static void loadFromDir(FileSystem fs, String dir,
            List<PromptTemplate> templates, List<Diagnostic> diagnostics) {
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
            loadFromFile(fs, entry.path(), templates, diagnostics);
        }
    }

    private static void loadFromFile(FileSystem fs, String filePath,
            List<PromptTemplate> templates, List<Diagnostic> diagnostics) {
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
        templates.add(new PromptTemplate(fileName, description, parsed.body()));
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
     * 用命令参数替换模板占位符（pi {@code substituteArgs}）：{@code $1}、{@code $@}、
     * {@code $ARGUMENTS}、{@code ${@:N}}、{@code ${@:N:L}}。
     */
    public static String substituteArgs(String content, List<String> args) {
        String result = replaceAll(Pattern.compile("\\$(\\d+)"), content, m -> {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 && n <= args.size() ? args.get(n - 1) : "";
        });
        result = replaceAll(Pattern.compile("\\$\\{@:(\\d+)(?::(\\d+))?\\}"), result, m -> {
            int start = Integer.parseInt(m.group(1)) - 1;
            if (start < 0) {
                start = 0;
            }
            if (m.group(2) != null) {
                int length = Integer.parseInt(m.group(2));
                return args.stream().skip(start).limit(length).reduce((a, b) -> a + " " + b).orElse("");
            }
            return args.stream().skip(start).reduce((a, b) -> a + " " + b).orElse("");
        });
        String allArgs = String.join(" ", args);
        result = result.replace("$ARGUMENTS", allArgs);
        result = result.replace("$@", allArgs);
        return result;
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
