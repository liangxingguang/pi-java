package com.pijava.coding.agent.pkg;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * pi 资源过滤代数（package-manager.ts:283-313/:661-810）逐字对位。条目四形：
 *
 * <ul>
 *   <li>普通 pattern：命中即收（无 include 时默认全收）；</li>
 *   <li>{@code !pattern}：排除；</li>
 *   <li>{@code +path}：强制收（精确路径，压过排除）；</li>
 *   <li>{@code -path}：强制排除（精确路径，压过强制收）。</li>
 * </ul>
 *
 * <p>匹配三形态（相对基目录／文件名／绝对路径）跑 {@link MinimatchLite}；
 * {@code SKILL.md} 额外对父目录三形态再跑一遍。精确形只做字符串等值。</p>
 */
public final class ResourcePatterns {

    private ResourcePatterns() {}

    /** pi {@code isPattern}（:283-285）。 */
    public static boolean isPattern(String s) {
        return s.startsWith("!") || s.startsWith("+") || s.startsWith("-")
            || s.contains("*") || s.contains("?");
    }

    /** pi {@code isOverridePattern}（:287-289）。 */
    public static boolean isOverridePattern(String s) {
        return s.startsWith("!") || s.startsWith("+") || s.startsWith("-");
    }

    /** pi {@code hasGlobPattern}（:291-293）。 */
    public static boolean hasGlobPattern(String s) {
        return s.contains("*") || s.contains("?");
    }

    /** pi {@code splitPatterns}（:303-313）。 */
    public static SplitPatterns splitPatterns(List<String> entries) {
        var plain = new ArrayList<String>();
        var patterns = new ArrayList<String>();
        for (var entry : entries) {
            if (isPattern(entry)) {
                patterns.add(entry);
            } else {
                plain.add(entry);
            }
        }
        return new SplitPatterns(plain, patterns);
    }

    public record SplitPatterns(List<String> plain, List<String> patterns) {}

    /** pi {@code matchesAnyPattern}（:661-687）——三形态 minimatch；
     *  {@code SKILL.md} 追加父目录三形态。 */
    public static boolean matchesAnyPattern(String filePath, List<String> patterns, String baseDir) {
        var rel = toPosixPath(relative(baseDir, filePath));
        var name = basename(filePath);
        var filePathPosix = toPosixPath(filePath);
        var isSkillFile = name.equals("SKILL.md");
        String parentRel = null;
        String parentName = null;
        String parentDirPosix = null;
        if (isSkillFile) {
            var parentDir = dirname(filePath);
            parentRel = toPosixPath(relative(baseDir, parentDir));
            parentName = basename(parentDir);
            parentDirPosix = toPosixPath(parentDir);
        }

        for (var pattern : patterns) {
            var normalizedPattern = toPosixPath(pattern);
            if (MinimatchLite.match(normalizedPattern, rel)
                || MinimatchLite.match(normalizedPattern, name)
                || MinimatchLite.match(normalizedPattern, filePathPosix)) {
                return true;
            }
            if (!isSkillFile) {
                continue;
            }
            if (MinimatchLite.match(normalizedPattern, parentRel)
                || MinimatchLite.match(normalizedPattern, parentName)
                || MinimatchLite.match(normalizedPattern, parentDirPosix)) {
                return true;
            }
        }
        return false;
    }

    /** pi {@code normalizeExactPattern}（:689-692）：剥 {@code ./} 前缀＋posix。 */
    public static String normalizeExactPattern(String pattern) {
        var normalized = pattern.startsWith("./") || pattern.startsWith(".\\")
            ? pattern.substring(2) : pattern;
        return toPosixPath(normalized);
    }

    /** pi {@code matchesAnyExactPattern}（:694-712）——字符串等值（相对/绝对/
     *  SKILL.md 父目录两形态）。 */
    public static boolean matchesAnyExactPattern(String filePath, List<String> patterns, String baseDir) {
        if (patterns.isEmpty()) {
            return false;
        }
        var rel = toPosixPath(relative(baseDir, filePath));
        var filePathPosix = toPosixPath(filePath);
        var isSkillFile = basename(filePath).equals("SKILL.md");
        String parentRel = null;
        String parentDirPosix = null;
        if (isSkillFile) {
            var parentDir = dirname(filePath);
            parentRel = toPosixPath(relative(baseDir, parentDir));
            parentDirPosix = toPosixPath(parentDir);
        }

        for (var pattern : patterns) {
            var normalized = normalizeExactPattern(pattern);
            if (normalized.equals(rel) || normalized.equals(filePathPosix)) {
                return true;
            }
            if (!isSkillFile) {
                continue;
            }
            if (normalized.equals(parentRel) || normalized.equals(parentDirPosix)) {
                return true;
            }
        }
        return false;
    }

    /** pi {@code getOverridePatterns}（:714-716）。 */
    public static List<String> getOverridePatterns(List<String> entries) {
        return entries.stream().filter(ResourcePatterns::isOverridePattern).toList();
    }

    /** pi {@code isEnabledByOverrides}（:718-733）：默认 enabled=true，! 排除 →
     *  + 强制收 → - 强制排除（后写压前写）。 */
    public static boolean isEnabledByOverrides(String filePath, List<String> patterns, String baseDir) {
        var overrides = getOverridePatterns(patterns);
        var excludes = stripPrefix(overrides, "!");
        var forceIncludes = stripPrefix(overrides, "+");
        var forceExcludes = stripPrefix(overrides, "-");

        var enabled = true;
        if (!excludes.isEmpty() && matchesAnyPattern(filePath, excludes, baseDir)) {
            enabled = false;
        }
        if (!forceIncludes.isEmpty() && matchesAnyExactPattern(filePath, forceIncludes, baseDir)) {
            enabled = true;
        }
        if (!forceExcludes.isEmpty() && matchesAnyExactPattern(filePath, forceExcludes, baseDir)) {
            enabled = false;
        }
        return enabled;
    }

    /**
     * pi {@code applyPatterns}（:745-791）：include（无则全收）→ exclude →
     * 强制收（精确，回补）→ 强制排除（精确，再删）。
     */
    public static Set<String> applyPatterns(List<String> allPaths, List<String> patterns, String baseDir) {
        var includes = new ArrayList<String>();
        var excludes = new ArrayList<String>();
        var forceIncludes = new ArrayList<String>();
        var forceExcludes = new ArrayList<String>();

        for (var p : patterns) {
            if (p.startsWith("+")) {
                forceIncludes.add(p.substring(1));
            } else if (p.startsWith("-")) {
                forceExcludes.add(p.substring(1));
            } else if (p.startsWith("!")) {
                excludes.add(p.substring(1));
            } else {
                includes.add(p);
            }
        }

        List<String> result;
        if (includes.isEmpty()) {
            result = new ArrayList<>(allPaths);
        } else {
            result = allPaths.stream()
                .filter(f -> matchesAnyPattern(f, includes, baseDir))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        if (!excludes.isEmpty()) {
            result.removeIf(f -> matchesAnyPattern(f, excludes, baseDir));
        }

        if (!forceIncludes.isEmpty()) {
            for (var filePath : allPaths) {
                if (!result.contains(filePath)
                    && matchesAnyExactPattern(filePath, forceIncludes, baseDir)) {
                    result.add(filePath);
                }
            }
        }

        if (!forceExcludes.isEmpty()) {
            result.removeIf(f -> matchesAnyExactPattern(f, forceExcludes, baseDir));
        }

        return new LinkedHashSet<>(result);
    }

    /** pi {@code applyAutoloadDisabledPatterns}（:793-810）：产 路径→enabled
     *  映射（{@code +}/普通＝true，{@code !}/{@code -}＝false；{@code +}/{@code -}
     *  精确、其他 pattern）；LinkedHashMap 保 pi Map 插入序。 */
    public static Map<String, Boolean> applyAutoloadDisabledPatterns(
            List<String> allPaths, List<String> patterns, String baseDir) {
        var result = new LinkedHashMap<String, Boolean>();
        for (var pattern : patterns) {
            var hasPrefix = pattern.startsWith("+") || pattern.startsWith("-") || pattern.startsWith("!");
            var target = pattern.substring(hasPrefix ? 1 : 0);
            var enabled = !pattern.startsWith("-") && !pattern.startsWith("!");
            var exact = pattern.startsWith("+") || pattern.startsWith("-");
            for (var filePath : allPaths) {
                var matched = exact
                    ? matchesAnyExactPattern(filePath, List.of(target), baseDir)
                    : matchesAnyPattern(filePath, List.of(target), baseDir);
                if (matched) {
                    result.put(filePath, enabled);
                }
            }
        }
        return result;
    }

    private static List<String> stripPrefix(List<String> patterns, String prefix) {
        return patterns.stream()
            .filter(p -> p.startsWith(prefix))
            .map(p -> p.substring(1))
            .toList();
    }

    /** pi {@code toPosixPath}（:227-229）：分隔符归一为 {@code /}。 */
    public static String toPosixPath(String path) {
        return path.replace(File.separatorChar, '/');
    }

    /** node {@code path.relative} 对位（跨根/非法路径字符——如 {@code builtin:x}
     *  在 Windows 含 {@code :}——回退原串；pi 此处纯串运算不抛）。 */
    private static String relative(String baseDir, String filePath) {
        try {
            return Path.of(baseDir).toAbsolutePath().normalize()
                .relativize(Path.of(filePath).toAbsolutePath().normalize())
                .toString();
        } catch (RuntimeException e) {
            return filePath;
        }
    }

    /** node {@code path.basename} 对位（纯串，兼容两向分隔符）。 */
    private static String basename(String path) {
        var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(idx + 1);
    }

    /** node {@code path.dirname} 对位（纯串；无分隔符 ⇒ 原串）。 */
    private static String dirname(String path) {
        var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(0, idx);
    }
}
