package com.pijava.coding.agent.pkg;

import java.util.regex.Pattern;

import com.pijava.coding.agent.core.PackageSource;

/**
 * pi {@code parseSource}/{@code parseNpmSpec}（package-manager.ts:1480-1505/
 * :1765-1773）＋ {@code isLocalPath}（utils/paths.ts:50-65）——上下文无关的
 * 源解析面。分派序逐字：{@code npm:} → local 前缀表 → git URL → local 恒定回落。
 */
public final class SourceParser {

    /** pi {@code parseNpmSpec} 正则逐字：scoped 名 {@code @org/pkg} 的首个
     *  {@code @} 不算分隔。 */
    private static final Pattern NPM_SPEC = Pattern.compile("^(@?[^@]+(?:/[^@]+)?)(?:@(.+))?$");

    private SourceParser() {}

    /** pi {@code parseSource} 逐字对位。 */
    public static ParsedSource parseSource(String source) {
        if (source.startsWith("npm:")) {
            var spec = source.substring("npm:".length()).trim();
            var nameVersion = parseNpmSpec(spec);
            return new ParsedSource.NpmSource(
                spec,
                nameVersion.name(),
                nameVersion.version(),
                getNpmVersionRange(nameVersion.version()),
                isExactNpmVersion(nameVersion.version()));
        }
        if (isLocalPath(source)) {
            return new ParsedSource.LocalSource(source);
        }
        var gitParsed = GitUrlParser.parseGitUrl(source);
        if (gitParsed != null) {
            return gitParsed;
        }
        return new ParsedSource.LocalSource(source);
    }

    /**
     * pi {@code isLocalPath} 逐字：非 {@code npm:/git:/github:/http:/https:/
     * ssh:/builtin:} 前缀一律本地；{@code file:} 也算本地（由 resolvePath 解析）。
     */
    public static boolean isLocalPath(String value) {
        var trimmed = value.trim();
        return !(trimmed.startsWith("npm:")
            || trimmed.startsWith("git:")
            || trimmed.startsWith("github:")
            || trimmed.startsWith("http:")
            || trimmed.startsWith("https:")
            || trimmed.startsWith("ssh:")
            || trimmed.startsWith("builtin:"));
    }

    /** pi {@code getPackageSourceString}（:1403-1405）：对象形取 {@code source}。 */
    public static String getPackageSourceString(PackageSource pkg) {
        return pkg.source();
    }

    /** pi {@code isExactNpmVersion}（:60-62）：精确 semver ⇒ pinned。 */
    static boolean isExactNpmVersion(String version) {
        return Semvers.valid(version == null ? "" : version) != null;
    }

    /** pi {@code getNpmVersionRange}（:64-66）：合法 range 原样返回。 */
    static String getNpmVersionRange(String version) {
        return version == null ? null : Semvers.validRange(version);
    }

    /** pi {@code parseNpmSpec}（:1765-1773）。 */
    static NameVersion parseNpmSpec(String spec) {
        var matcher = NPM_SPEC.matcher(spec);
        if (!matcher.matches()) {
            return new NameVersion(spec, null);
        }
        var name = matcher.group(1) != null ? matcher.group(1) : spec;
        return new NameVersion(name, matcher.group(2));
    }

    /** {@code parseNpmSpec} 输出。 */
    record NameVersion(String name, String version) {}
}
