package com.pijava.evals.contents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * L6 W3（docs/26 §4.4）：审计的「实现源」种入选集 —— pi 直接只读挂载整个仓库
 * （{@code documentation-audit.eval.ts:32-34}），Java 的工具受 {@code PathUtils}
 * 限制只能在 workspace 内读，故必须把源种进隔离 workspace。
 *
 * <p>全量种入是 900 个文件／约 11 万行，对每个审计用例都做一遍不划算。本类按页面
 * 内容挑出**相关源**：</p>
 *
 * <ol>
 *   <li>页面上出现的类名（{@code Foo.java}／{@code Foo}）直接命中同名源文件；</li>
 *   <li>页面上出现的包路径片段（如 {@code com.pijava.coding.agent.core}）命中该包下全部源；</li>
 *   <li>无论步骤 1/2 是否命中，都补上该页面可能引用的核心锚点文件（见 {@link #ANCHORS}），
 *       保证「未读实现」永远是可区分的失败原因，而不是种入不全导致的假阴性。</li>
 * </ol>
 *
 * <p>这是与 pi 的形状差：pi 读全仓、Java 读子集，登记 D230。子集只影响**可读范围**，
 * 不影响判分口径——取证断言仍然要求「读过页面」＋「读过实现源」。</p>
 */
public final class AuditSourceIndex {

    /** 无论页面写什么都会被种入的锚点源（相对仓库根）。 */
    public static final List<String> ANCHORS = List.of(
        "pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java",
        "pi-java-coding-agent/src/main/java/com/pijava/coding/agent/cli/ArgsParser.java",
        "pi-java-ai/src/main/java/com/pijava/ai/provider/ProviderRegistry.java");

    /** 参与索引的模块根（相对仓库根）。 */
    public static final List<String> MODULE_ROOTS = List.of(
        "pi-java-ai", "pi-java-agent-core", "pi-java-coding-agent");

    private final Map<String, List<String>> sourcesByStem;
    private final List<String> allSources;

    private AuditSourceIndex(Map<String, List<String>> sourcesByStem, List<String> allSources) {
        this.sourcesByStem = sourcesByStem;
        this.allSources = allSources;
    }

    /**
     * Build the index by scanning the modules' {@code src/main/java}.
     *
     * @param repoRoot repository root
     * @return index (empty when no module exists)
     */
    public static AuditSourceIndex scan(Path repoRoot) {
        var byStem = new LinkedHashMap<String, List<String>>();
        var all = new ArrayList<String>();
        for (var module : MODULE_ROOTS) {
            var root = repoRoot.resolve(module).resolve("src").resolve("main").resolve("java");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                var sources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> repoRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
                for (var source : sources) {
                    all.add(source);
                    var fileName = source.substring(source.lastIndexOf('/') + 1);
                    byStem.computeIfAbsent(fileName.substring(0, fileName.length() - ".java".length()),
                        ignored -> new ArrayList<>()).add(source);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to index audit sources under " + root, e);
            }
        }
        return new AuditSourceIndex(byStem, List.copyOf(all));
    }

    /** @return every indexed source path (repo-relative, {@code /} separators) */
    public List<String> allSources() {
        return allSources;
    }

    /**
     * Pick the sources relevant to one documentation page.
     *
     * @param page     page name (its stem drives the match)
     * @param contents page body
     * @return relevant sources, anchors included, sorted and unique
     */
    public List<String> sourcesFor(String page, String contents) {
        var picked = new TreeSet<String>();
        picked.addAll(ANCHORS);
        if (contents != null) {
            for (var stem : stemsIn(contents)) {
                picked.addAll(sourcesByStem.getOrDefault(stem, List.of()));
            }
            for (var pkg : packagesIn(contents)) {
                var prefix = pkg.replace('.', '/') + "/";
                for (var source : allSources) {
                    if (source.contains(prefix)) {
                        picked.add(source);
                    }
                }
            }
        }
        // 页面自身名字也参与（docs/25-l5-coding-agent-*.md ⇒ 命中 CodingAgent 族）
        var pageTokens = tokens(page);
        for (var entry : sourcesByStem.entrySet()) {
            if (pageTokens.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                picked.addAll(entry.getValue());
            }
        }
        return picked.stream().filter(allSources::contains).toList();
    }

    /**
     * Read the picked sources into a workspace-relative fixture map.
     *
     * @param repoRoot repository root
     * @param sources  repo-relative source paths
     * @return path → content
     */
    public static Map<String, String> read(Path repoRoot, List<String> sources) {
        var files = new LinkedHashMap<String, String>();
        for (var source : sources) {
            var path = repoRoot.resolve(source);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                files.put(source, Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read audit source " + source, e);
            }
        }
        return files;
    }

    /** {@code SomeClass} / {@code SomeClass.java} 形式的词。 */
    static Set<String> stemsIn(String contents) {
        var stems = new LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern
            .compile("\\b([A-Z][A-Za-z0-9_]{2,})(?:\\.java)?\\b")
            .matcher(contents);
        while (matcher.find()) {
            stems.add(matcher.group(1));
        }
        return stems;
    }

    /** {@code com.pijava.*} 形式的包名。 */
    static Set<String> packagesIn(String contents) {
        var packages = new LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern
            .compile("\\b(com\\.pijava(?:\\.[a-z][a-z0-9_]*)+)")
            .matcher(contents);
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        return packages;
    }

    private static Set<String> tokens(String page) {
        var tokens = new LinkedHashSet<String>();
        var name = page.substring(page.lastIndexOf('/') + 1);
        if (name.endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        for (var part : name.split("[^A-Za-z0-9]+")) {
            if (part.length() > 2) {
                tokens.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }
}
