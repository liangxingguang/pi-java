package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import com.pijava.agent.prompt.SystemPromptOptions;

/**
 * 批 30（T6，B95）：pi-java 发行根解析——pi {@code config.ts} 的
 * {@code getPackageDir}/{@code getReadmePath}/{@code getDocsPath}/{@code getExamplesPath}
 * （:391-404/:444-457）对位。
 *
 * <p>解析序（pi 同序，逐臂对位）：</p>
 * <ol>
 *   <li>环境变量 {@code PI_JAVA_PACKAGE_DIR}（pi {@code PI_PACKAGE_DIR} 对位）；</li>
 *   <li>从本类代码源位置（{@code getProtectionDomain().getCodeSource()}）向上找
 *       <b>同时含 {@code README.md} 与 {@code docs/}</b> 的目录——pi-java 没有
 *       package.json（pi 的 {@code findNodePackageDir} 标记）；pom.xml 每层模块都有，
 *       不能当根标记；README＋docs 组合只有仓根满足；</li>
 *   <li>找不到 ⇒ {@code null} ⇒ docs 段不产出（{@code docsSection} 的 null 臂既有）。</li>
 * </ol>
 *
 * <p>native image 无代码源位置 ⇒ 走 env 或不产出（docs/42 裁决②）。</p>
 */
public final class PiJavaPaths {

    /** pi {@code PI_PACKAGE_DIR} 的环境变量对位。 */
    public static final String PACKAGE_DIR_ENV = "PI_JAVA_PACKAGE_DIR";

    private PiJavaPaths() {}

    /** pi-java 发行根；解析不到 ⇒ {@code null}。 */
    public static @Nullable Path packageDir() {
        return packageDir(System.getenv(PACKAGE_DIR_ENV), codeSourceStart());
    }

    /**
     * 解析内核（测试缝）：env 覆盖优先 ⇒ 从 {@code start} 向上找根标记。
     *
     * @param envDir {@link #PACKAGE_DIR_ENV} 的现读值（null/空白 ≙ 缺席）
     * @param start  向上查找的起点（生产＝本类代码源位置；null ⇒ 只有 env 臂可走）
     */
    static @Nullable Path packageDir(@Nullable String envDir, @Nullable Path start) {
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir).toAbsolutePath().normalize();
        }
        if (start == null) {
            return null;
        }
        // jar 运行时 location 指向 jar 文件本身 ⇒ 从其所在目录起找。
        if (!Files.isDirectory(start)) {
            start = start.getParent();
        }
        for (var dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("README.md"))
                    && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
        }
        return null;
    }

    /** 本类代码源位置（file: 协议才可用；native image/异形容器 ⇒ null）。 */
    private static @Nullable Path codeSourceStart() {
        try {
            var source = PiJavaPaths.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null
                    || !"file".equals(source.getLocation().getProtocol())) {
                return null;
            }
            return Path.of(source.getLocation().toURI());
        } catch (Exception e) {
            // 代码源位置不可解析（SecurityManager/异形容器）⇒ 不产出。
            return null;
        }
    }

    /**
     * 系统提示 {@code docs} 段的路径束；发行根解析不到 ⇒ {@code null}。
     *
     * <p>{@code pi-java-examples/} 存在才填 examples（{@code DocumentationPaths.examples}
     * 可空，{@code docsSection} 有既有条件臂）。</p>
     */
    public static SystemPromptOptions.@Nullable DocumentationPaths documentation() {
        var root = packageDir();
        return root == null ? null : documentation(root);
    }

    /** 从已知根建路径束（测试缝）；examples 目录存在才填。 */
    static SystemPromptOptions.DocumentationPaths documentation(Path root) {
        var examples = root.resolve("pi-java-examples");
        return new SystemPromptOptions.DocumentationPaths(
            slashify(root.resolve("README.md")),
            slashify(root.resolve("docs")),
            Files.isDirectory(examples) ? slashify(examples) : null);
    }

    /** pi {@code normalizePath} 对位：反斜杠一律换正斜杠。 */
    private static String slashify(Path path) {
        return path.toString().replace('\\', '/');
    }
}
