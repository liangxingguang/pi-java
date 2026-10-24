package com.pijava.coding.agent.pkg;

/**
 * pi {@code ParsedSource}（package-manager.ts 源解析三型）——变体携带不同字段
 * ⇒ sealed interface + record（ADT 规范）。
 */
public sealed interface ParsedSource {

    /**
     * npm 源。{@code range}＝版本是合法 range 时的原样 range 串（pi
     * {@code getNpmVersionRange}）；{@code pinned}＝精确 semver（pi
     * {@code isExactNpmVersion}）。
     */
    record NpmSource(String spec, String name, String version, String range, boolean pinned)
        implements ParsedSource {}

    /** git 源（utils/git.ts {@code GitSource}）。{@code pinned = ref != null}。 */
    record GitSource(String repo, String host, String path, String ref, boolean pinned)
        implements ParsedSource {}

    /** 本地路径源（含一切解析失败的恒定回落，pi {@code parseSource} 尾臂）。 */
    record LocalSource(String path) implements ParsedSource {}
}
