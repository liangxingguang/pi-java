package com.pijava.coding.agent.core.source;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.coding.agent.core.source.SourceInfo.Origin;
import com.pijava.coding.agent.core.source.SourceInfo.Scope;

/**
 * 批 23（docs/34 T4）：扩展路径的 {@link SourceInfo} 计算——pi
 * {@code getDefaultSourceInfoForPath}（resource-loader.ts:978-1024）的扩展面移植
 * （pi 同一函数服务四种资源根；java 侧只有扩展路径消费者，只查 extensions 根）。
 *
 * <p>规则逐字：合成路径（{@code builtin:<name>}／{@code <inline:...>}）⇒ scope 恒
 * temporary、origin 恒 top-level、source 取前缀（空串回落 {@code "temporary"}）；
 * 文件路径落在 {@code <agentDir>/extensions} ⇒ user 域、{@code <projectDir>/
 * .pi-java/extensions} ⇒ project 域（source 恒 "local"、带 baseDir——目录名随
 * B182 的 .pi-java 约定）；其余 ⇒ temporary，baseDir＝目录自身/文件父目录
 * （pi statSync 臂）。</p>
 */
public final class ExtensionSourceInfos {

    private ExtensionSourceInfos() {
    }

    /**
     * 计算扩展路径的 sourceInfo。
     *
     * @param rawPath    扩展路径（合成串或文件路径；pi {@code extension.path}）
     * @param agentDir   全局 agent 目录（{@code null} ⇒ 跳过 user 根判定）
     * @param projectDir 项目目录（{@code null} ⇒ 跳过 project 根判定）
     */
    public static SourceInfo forExtensionPath(String rawPath, Path agentDir, Path projectDir) {
        var synthetic = syntheticSource(rawPath);
        if (synthetic != null) {
            return new SourceInfo(rawPath, synthetic, Scope.TEMPORARY, Origin.TOP_LEVEL, null);
        }
        var normalized = Path.of(rawPath).toAbsolutePath().normalize();
        if (agentDir != null) {
            var root = agentDir.resolve("extensions").toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                return new SourceInfo(rawPath, "local", Scope.USER, Origin.TOP_LEVEL,
                    root.toString());
            }
        }
        if (projectDir != null) {
            var root = projectDir.resolve(".pi-java").resolve("extensions")
                .toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                return new SourceInfo(rawPath, "local", Scope.PROJECT, Origin.TOP_LEVEL,
                    root.toString());
            }
        }
        var baseDir = Files.isDirectory(normalized) ? normalized : normalized.getParent();
        return new SourceInfo(rawPath, "local", Scope.TEMPORARY, Origin.TOP_LEVEL,
            baseDir == null ? null : baseDir.toString());
    }

    /** pi {@code getSyntheticPathSource}（source-info.ts:19-24 逐字）。 */
    private static String syntheticSource(String path) {
        if (path.startsWith("builtin:")) {
            return "builtin";
        }
        if (path.startsWith("<") && path.endsWith(">")) {
            var inner = path.substring(1, path.length() - 1);
            var colon = inner.indexOf(':');
            var prefix = colon == -1 ? inner : inner.substring(0, colon);
            return prefix.isEmpty() ? "temporary" : prefix;
        }
        return null;
    }
}
