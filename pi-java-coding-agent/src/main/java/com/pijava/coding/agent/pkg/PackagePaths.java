package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 包管理器路径件（L5 W4 拆件，docs/04 结构债惯例）：{@code canonicalizePath} 与
 * {@code resolvePackageChangelogPath}。
 *
 * <p>两者都只做纯路径计算、不持状态；从 {@link PackageManager} 顶出以守住
 * 文件 ≤500 行闸门（行为零变化；{@code PackageManager.canonicalizePath} 保留
 * 同签名委托，上游三处消费点不动）。</p>
 */
public final class PackagePaths {

    /** pi {@code resolvePackageChangelogPath} 的缺省文件名（package-manager.ts:697）。 */
    private static final String DEFAULT_CHANGELOG_FILE = "CHANGELOG.md";

    private PackagePaths() {
    }

    /** pi {@code canonicalizePath}（utils/paths.ts:27-33）：realpath 失败回原串。
     *  java 对位 {@code toRealPath(NOFOLLOW_LINKS)}（docs/29 §3 T5 裁决文）。
     *  批 19 转 public：config TUI 的继承态键（{@code type:canonicalizePath}，
     *  config-selector.ts:850-852）共用。 */
    public static String canonicalizePath(String path) {
        try {
            return Path.of(path).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
        } catch (IOException | java.nio.file.InvalidPathException | SecurityException e) {
            return path;
        }
    }

    /**
     * L5 W4（pi {@code resolvePackageChangelogPath}，package-manager.ts:697-713）：
     * 缺省 {@code CHANGELOG.md}；必须真实存在、是普通文件、canonical 路径在包根内。
     *
     * @return 绝对路径；任一条件不满足 ⇒ {@code null}
     */
    public static String resolvePackageChangelogPath(String packageRoot,
                                                     PiManifest manifest) {
        var changelogPath = manifest != null && manifest.changelogPath() != null
            ? manifest.changelogPath() : DEFAULT_CHANGELOG_FILE;
        if (changelogPath.isEmpty()) {
            return null;
        }
        var resolved = Path.of(packageRoot).resolve(changelogPath);
        if (!Files.exists(resolved)) {
            return null;
        }
        var canonicalRoot = Path.of(canonicalizePath(packageRoot));
        var canonicalChangelog = Path.of(canonicalizePath(resolved.toString()));
        if (!isPathInside(canonicalRoot, canonicalChangelog)) {
            return null;
        }
        return Files.isRegularFile(resolved) ? resolved.toString() : null;
    }

    /** pi {@code isPathInside}（package-manager.ts:690-695 逐字）：相对路径非空、
     *  非 {@code ".."}、不以 {@code "../"} 起头且非绝对（排除包根本身与逃逸）。 */
    private static boolean isPathInside(Path parent, Path child) {
        Path relative;
        try {
            relative = parent.relativize(child);
        } catch (IllegalArgumentException e) {
            // 不同根（Windows 跨盘）⇒ 不在内部。
            return false;
        }
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            return false;
        }
        var text = relative.toString();
        return !text.isEmpty() && !text.equals("..")
            && !text.startsWith(".." + java.io.File.separator);
    }
}
