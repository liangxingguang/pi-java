package com.pijava.coding.agent.pkg;

import java.nio.file.Path;
import java.util.Locale;

/**
 * L5 W5 §6.4（pi {@code utils/paths.ts:36-78}）：文件系统路径的<b>比较键</b>——
 * 绝对化＋{@code normalize}，Windows 上再折叠大小写（NTFS 大小写不敏感；
 * 逐目录大小写敏感极少见，pi 同样忽略）。
 *
 * <p>只用于比较，不用于显示、也不用于访问文件系统（pi 注释逐字）。需要符号
 * 链接等价时先跑 {@link PackagePaths#canonicalizePath(String)}。</p>
 *
 * <p>{@link #isWithin}、{@link #resourceKey} 分别对位 pi {@code isPathWithin}
 * 与 {@code source-info.ts:getResourcePathKey}。</p>
 */
public final class PathComparisonKeys {

    private PathComparisonKeys() {}

    /** pi {@code getPathComparisonKey}：resolve 后 Windows 折叠大小写。 */
    public static String key(String path) {
        var resolved = resolve(path);
        return isWindows() ? resolved.toLowerCase(Locale.ROOT) : resolved;
    }

    /** pi {@code pathsEqual}：两者比较键相等。 */
    public static boolean equal(String left, String right) {
        return key(left).equals(key(right));
    }

    /** pi {@code isPathWithin}：{@code child} 是 {@code parent} 自身或其内部。 */
    public static boolean isWithin(String parent, String child) {
        var parentKey = key(parent);
        var childKey = key(child);
        if (childKey.equals(parentKey)) {
            return true;
        }
        // 文件系统根（"/"、"C:\"）本身已带分隔符（pi 逐字）。
        var sep = java.io.File.separator;
        return childKey.startsWith(parentKey.endsWith(sep) ? parentKey : parentKey + sep);
    }

    /** pi {@code isSyntheticPath}（source-info.ts:41-43）：{@code builtin:}/{@code <...>}
     *  等不指向文件的路径。 */
    public static boolean isSynthetic(String path) {
        return path.startsWith(PackageManager.BUILTIN_PATH_PREFIX) || path.startsWith("<");
    }

    /** pi {@code getResourcePathKey}（source-info.ts:46-48）：合成路径原样，
     *  文件路径 canonicalize 后取比较键——资源去重键。 */
    public static String resourceKey(String path) {
        return isSynthetic(path) ? path
            : key(PackagePaths.canonicalizePath(path));
    }

    /** node {@code path.resolve} 对位：绝对化＋normalize；非法路径（Windows 含
     *  {@code :} 的合成串）回原串（pi 纯串运算不抛）。 */
    private static String resolve(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (java.nio.file.InvalidPathException e) {
            return path;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
