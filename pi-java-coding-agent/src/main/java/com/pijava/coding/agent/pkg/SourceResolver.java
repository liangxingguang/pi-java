package com.pijava.coding.agent.pkg;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.pijava.coding.agent.core.PackageSource;

/**
 * pi {@code DefaultPackageManager} 的作用域/身份/查重面（上下文绑定：cwd、
 * agentDir、信任门）——
 *
 * <ul>
 *   <li>{@code getBaseDirForScope}/{@code assertProjectTrustedForScope}
 *       （:2195-2204/:1775-1779）</li>
 *   <li>{@code resolvePath}/{@code resolvePathFromBase}（:2206-2213 ＋
 *       utils/paths.ts {@code resolvePath}）</li>
 *   <li>{@code getPackageIdentity}/{@code dedupePackages}（:1721-1763）</li>
 *   <li>{@code packageSourcesMatch}/{@code normalizePackageSourceForSettings}
 *       （:1463-1478，写回链工具，批 19 用）</li>
 * </ul>
 *
 * <p>java 侧 {@code CONFIG_DIR_NAME} 用既定 {@code ".pi-java"} 翻译。</p>
 */
public final class SourceResolver {

    /** pi {@code CONFIG_DIR_NAME}（".pi"）的 java 既定翻译。 */
    public static final String CONFIG_DIR_NAME = ".pi-java";

    private final Path cwd;
    private final Path agentDir;
    private final BooleanSupplier projectTrusted;

    public SourceResolver(Path cwd, Path agentDir, BooleanSupplier projectTrusted) {
        this.cwd = cwd.toAbsolutePath().normalize();
        this.agentDir = agentDir.toAbsolutePath().normalize();
        this.projectTrusted = projectTrusted;
    }

    /** pi 构造对位：cwd/agentDir 暴露为解析后形态。 */
    public Path cwd() {
        return cwd;
    }

    public Path agentDir() {
        return agentDir;
    }

    /** pi {@code getBaseDirForScope}（:2195-2204）。 */
    public Path getBaseDirForScope(SourceScope scope) {
        if (scope == SourceScope.PROJECT) {
            assertProjectTrustedForScope(scope);
            return cwd.resolve(CONFIG_DIR_NAME);
        }
        if (scope == SourceScope.USER) {
            return agentDir;
        }
        return cwd;
    }

    /** pi {@code assertProjectTrustedForScope}（:1775-1779）——模型可见文案逐字。 */
    public void assertProjectTrustedForScope(SourceScope scope) {
        if (scope == SourceScope.PROJECT && !projectTrusted.getAsBoolean()) {
            throw new IllegalStateException(
                "Project is not trusted; refusing to access project package storage");
        }
    }

    /** pi {@code resolvePath}（:2206-2208）：base＝cwd，trim＋tilde＋file://。 */
    public String resolvePath(String input) {
        return resolvePathInternal(input, cwd);
    }

    /** pi {@code resolvePathFromBase}（:2210-2212）。 */
    public String resolvePathFromBase(String input, Path baseDir) {
        return resolvePathInternal(input, baseDir);
    }

    /**
     * pi {@code getPackageIdentity}（:1721-1735）：去版本/ref 的查重键；
     * git 用 host/path 归一（SSH/HTTPS 同身份）。
     */
    public String getPackageIdentity(String source, SourceScope scope) {
        var parsed = SourceParser.parseSource(source);
        if (parsed instanceof ParsedSource.NpmSource npm) {
            return "npm:" + npm.name();
        }
        if (parsed instanceof ParsedSource.GitSource git) {
            return "git:" + git.host() + "/" + git.path();
        }
        var local = (ParsedSource.LocalSource) parsed;
        if (scope != null) {
            var baseDir = getBaseDirForScope(scope);
            return "local:" + resolvePathFromBase(local.path(), baseDir);
        }
        return "local:" + resolvePath(local.path());
    }

    /**
     * pi {@code dedupePackages}（:1742-1763）：同身份 project 赢 user；
     * project 条目 {@code autoload=false} 是 user 条目的 delta ⇒ 两条都留
     * （delta 在前）。
     */
    public List<ScopedPackage> dedupePackages(List<ScopedPackage> packages) {
        var result = new ArrayList<ScopedPackage>();
        var seen = new LinkedHashMap<String, Integer>();
        for (var entry : packages) {
            var identity = getPackageIdentity(
                SourceParser.getPackageSourceString(entry.pkg()), entry.scope());
            var index = seen.get(identity);
            if (index == null) {
                seen.put(identity, result.size());
                result.add(entry);
                continue;
            }
            var existing = result.get(index);
            if (existing.scope() == SourceScope.PROJECT && entry.scope() == SourceScope.USER) {
                if (Boolean.FALSE.equals(existing.pkg().autoload())) {
                    result.add(entry);
                }
            } else if (entry.scope() == SourceScope.PROJECT) {
                result.set(index, entry);
            }
        }
        return result;
    }

    /** pi {@code packageSourcesMatch}（:1463-1467）。 */
    public boolean packageSourcesMatch(PackageSource existing, String inputSource, SourceScope scope) {
        var left = getSourceMatchKeyForSettings(SourceParser.getPackageSourceString(existing), scope);
        var right = getSourceMatchKeyForInput(inputSource);
        return left.equals(right);
    }

    /**
     * pi {@code normalizePackageSourceForSettings}（:1469-1478）：local 源写成
     * 相对 scope 基目录的路径（批 19 写回链用）。
     */
    public String normalizePackageSourceForSettings(String source, SourceScope scope) {
        var parsed = SourceParser.parseSource(source);
        if (!(parsed instanceof ParsedSource.LocalSource local)) {
            return source;
        }
        var baseDir = getBaseDirForScope(scope);
        var resolved = Path.of(resolvePath(local.path()));
        var rel = relativize(baseDir, resolved);
        return rel.isEmpty() ? "." : rel;
    }

    private String getSourceMatchKeyForInput(String source) {
        var parsed = SourceParser.parseSource(source);
        if (parsed instanceof ParsedSource.NpmSource npm) {
            return "npm:" + npm.name();
        }
        if (parsed instanceof ParsedSource.GitSource git) {
            return "git:" + git.host() + "/" + git.path();
        }
        return "local:" + resolvePath(((ParsedSource.LocalSource) parsed).path());
    }

    private String getSourceMatchKeyForSettings(String source, SourceScope scope) {
        var parsed = SourceParser.parseSource(source);
        if (parsed instanceof ParsedSource.NpmSource npm) {
            return "npm:" + npm.name();
        }
        if (parsed instanceof ParsedSource.GitSource git) {
            return "git:" + git.host() + "/" + git.path();
        }
        var baseDir = getBaseDirForScope(scope);
        return "local:" + resolvePathFromBase(((ParsedSource.LocalSource) parsed).path(), baseDir);
    }

    /**
     * pi utils/paths.ts {@code resolvePath}：trim → {@code ~} 展开 →
     * {@code file://} 落地 → 绝对取自身/相对拼 baseDir，normalize（不实解析
     * 符号链接）。homeDir 每调现取（批 14 静态冻结教训）。
     */
    private static String resolvePathInternal(String input, Path baseDir) {
        var normalized = input.trim();
        var home = System.getProperty("user.home");
        if (normalized.equals("~")) {
            normalized = home;
        } else if (normalized.startsWith("~/")
            || (isWindows() && normalized.startsWith("~\\"))) {
            normalized = Path.of(home, normalized.substring(2)).toString();
        }
        if (normalized.startsWith("file://")) {
            try {
                normalized = Path.of(new URI(normalized)).toString();
            } catch (URISyntaxException | IllegalArgumentException e) {
                // pi fileURLToPath 同样拒绝非法 file URL；这里按字面路径继续。
            }
        }
        var path = Path.of(normalized);
        var resolved = path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
        return resolved.toString();
    }

    /** node {@code path.relative} 对位：跨根（Windows 异盘）回绝对路径。 */
    private static String relativize(Path baseDir, Path resolved) {
        try {
            return baseDir.relativize(resolved).toString();
        } catch (IllegalArgumentException e) {
            return resolved.toString();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /** pi dedupe 输入形 {@code {pkg, scope}}。 */
    public record ScopedPackage(PackageSource pkg, SourceScope scope) {}
}
