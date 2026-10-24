package com.pijava.coding.agent.pkg;

/**
 * pi {@code PathMetadata}（package-manager.ts:166-169 一带）。
 *
 * @param source      {@code "local"/"auto"/"builtin"}，或包来源串（origin=package 时）
 * @param scope       三档作用域
 * @param origin      {@code package}（包内资源）｜{@code top-level}（settings 条目/
 *                    自动发现）
 * @param baseDir     相对解析基目录（可空）
 * @param packageRoot 包根（可空）
 */
public record PathMetadata(
    String source,
    SourceScope scope,
    Origin origin,
    String baseDir,
    String packageRoot
) {

    /** pi origin 字面量闭集 ⇒ enum。 */
    public enum Origin {
        PACKAGE, TOP_LEVEL;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    public PathMetadata withBaseDir(String newBaseDir) {
        return new PathMetadata(source, scope, origin, newBaseDir, packageRoot);
    }

    public PathMetadata withPackageRoot(String newPackageRoot) {
        return new PathMetadata(source, scope, origin, baseDir, newPackageRoot);
    }

    /**
     * pi {@code resourcePrecedenceRank}（:171-185）逐字——五档优先级，小者先：
     * 0 project+settings 条目 / 1 project+自动发现 / 2 user+settings 条目 /
     * 3 user+自动发现 / 4 包 / 5 内置。
     */
    public static int resourcePrecedenceRank(PathMetadata m) {
        // Built-in extensions load after file and package extensions, whichever scope enables them.
        if (m.source.equals("builtin")) {
            return 5;
        }
        if (m.origin == Origin.PACKAGE) {
            return 4;
        }
        var scopeBase = m.scope == SourceScope.PROJECT ? 0 : 2;
        return scopeBase + (m.source.equals("local") ? 0 : 1);
    }
}
