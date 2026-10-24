package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.ignore.IgnoreNode;

/**
 * pi 忽略文件面（package-manager.ts:225-281）——三件文件名逐字
 * （{@code .gitignore}/{@code .ignore}/{@code .fdignore}），<b>拍平加前缀</b>
 * 模型：每个目录把三件文件的规则剥 {@code !}/{@code /} 后加相对根前缀，拍平进
 * 同一个 matcher（{@link #addRules}）。
 *
 * <p>matcher 复用 JGit {@link IgnoreNode} 逐条规则解析（逐行 parse 进同一
 * node ⇒ 拍平语义可保）。<b>窄差 D107</b>：pi {@code ignore} 包拍平模型 vs
 * JGit 层级解析的边角差（如目录限定写法/尾斜杠细节）。</p>
 */
public final class PackageIgnore {

    /** pi {@code IGNORE_FILE_NAMES}（:225）。 */
    public static final List<String> IGNORE_FILE_NAMES = List.of(".gitignore", ".ignore", ".fdignore");

    private final IgnoreNode node = new IgnoreNode();

    /** pi {@code addIgnoreRules}（:258-281）：dir 相对 rootDir 的前缀加给每条规则。 */
    public void addRules(Path dir, Path rootDir) {
        var relativeDir = rootDir.relativize(dir);
        var prefix = relativeDir.toString().isEmpty()
            ? "" : ResourcePatterns.toPosixPath(relativeDir.toString()) + "/";

        for (var filename : IGNORE_FILE_NAMES) {
            var ignorePath = dir.resolve(filename);
            if (!Files.exists(ignorePath)) {
                continue;
            }
            try {
                var patterns = new ArrayList<String>();
                for (var line : Files.readString(ignorePath, StandardCharsets.UTF_8)
                        .split("\r?\n", -1)) {
                    var pattern = prefixIgnorePattern(line, prefix);
                    if (pattern != null) {
                        patterns.add(pattern);
                    }
                }
                if (!patterns.isEmpty()) {
                    node.parse(new java.io.ByteArrayInputStream(
                        String.join("\n", patterns).getBytes(StandardCharsets.UTF_8)));
                }
            } catch (IOException e) {
                // pi catch {} 对位：读不动的忽略文件静默跳过
            }
        }
    }

    /** pi {@code ig.ignores(path)} 对位：目录由调用方以 isDirectory 表达
     *  （pi 拼尾 {@code /}）。 */
    public boolean ignores(String posixRelPath, boolean isDirectory) {
        Boolean result = node.checkIgnored(posixRelPath, isDirectory);
        return result != null && result;
    }

    /**
     * pi {@code prefixIgnorePattern}（:233-256）逐字：空/注释剔除；{@code !}
     * 否定与 {@code \!} 转义；前导 {@code /} 剥掉；加前缀后否定号归位。
     */
    static String prefixIgnorePattern(String line, String prefix) {
        var trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("#") && !trimmed.startsWith("\\#")) {
            return null;
        }

        var pattern = line;
        var negated = false;

        if (pattern.startsWith("!")) {
            negated = true;
            pattern = pattern.substring(1);
        } else if (pattern.startsWith("\\!")) {
            pattern = pattern.substring(1);
        }

        if (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }

        var prefixed = prefix.isEmpty() ? pattern : prefix + pattern;
        return negated ? "!" + prefixed : prefixed;
    }
}
