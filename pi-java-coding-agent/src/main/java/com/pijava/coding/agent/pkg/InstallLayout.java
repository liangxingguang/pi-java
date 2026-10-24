package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.coding.agent.core.Json;
import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * pi 安装布局面（package-manager.ts:2062-2193）——安装根/包装目录/temporary
 * 哈希检出/越根防护/{@code ensureNpmProject}/已装版本读取。
 *
 * <p>窄差：user 作用域无 {@code npm root -g}/pnpm 遗留全局回落（D102——HTTP
 * 实现只认 managed 安装根）；{@code markPathIgnoredByCloudSync}（macOS xattr）
 * no-op（D108）；temporary 目录 {@code 0700} Windows 无 ACL 对位，best-effort
 * （D109）。</p>
 */
public final class InstallLayout {

    private final SourceResolver resolver;

    public InstallLayout(SourceResolver resolver) {
        this.resolver = resolver;
    }

    // ── 安装根（:2085-2094 / :2166-2175）────────────────────────

    /** pi {@code getNpmInstallRoot}：temporary ⇒ 哈希目录；project ⇒ 信任门＋
     *  {@code <cwd>/.pi-java/npm}；user ⇒ {@code agentDir/npm}。 */
    public Path npmInstallRoot(SourceScope scope, boolean temporary) {
        if (temporary) {
            return temporaryDir("npm", null, null);
        }
        if (scope == SourceScope.PROJECT) {
            resolver.assertProjectTrustedForScope(scope);
            return resolver.cwd().resolve(SourceResolver.CONFIG_DIR_NAME).resolve("npm");
        }
        return resolver.agentDir().resolve("npm");
    }

    /** pi {@code getManagedNpmInstallPath}（:2126-2135）——managed 包装目录
     *  {@code <root>/node_modules/<name>}（scoped 名 {@code @org/pkg} 成两级目录）。 */
    public Path managedNpmInstallPath(NpmSource source, SourceScope scope) {
        if (scope == SourceScope.TEMPORARY) {
            return temporaryDir("npm", null, null).resolve("node_modules").resolve(source.name());
        }
        if (scope == SourceScope.PROJECT) {
            resolver.assertProjectTrustedForScope(scope);
            return resolver.cwd().resolve(SourceResolver.CONFIG_DIR_NAME)
                .resolve("npm").resolve("node_modules").resolve(source.name());
        }
        return resolver.agentDir().resolve("npm").resolve("node_modules").resolve(source.name());
    }

    /** pi {@code getNpmInstallPath}（:2145-2152）——java 无遗留全局回落（D102），
     *  即 managed 路径本身。 */
    public Path npmInstallPath(NpmSource source, SourceScope scope) {
        return managedNpmInstallPath(source, scope);
    }

    /** pi {@code getGitInstallRoot}（:2166-2175）：temporary ⇒ {@code null}。 */
    public Path gitInstallRoot(SourceScope scope) {
        if (scope == SourceScope.TEMPORARY) {
            return null;
        }
        if (scope == SourceScope.PROJECT) {
            resolver.assertProjectTrustedForScope(scope);
            return resolver.cwd().resolve(SourceResolver.CONFIG_DIR_NAME).resolve("git");
        }
        return resolver.agentDir().resolve("git");
    }

    /** pi {@code getGitInstallPath}（:2154-2164）。 */
    public Path gitInstallPath(GitSource source, SourceScope scope) {
        if (scope == SourceScope.TEMPORARY) {
            // Include the ref in the hash so each pinned ref gets its own checkout.
            return temporaryDir("git-" + source.host(), source.path(), source.ref());
        }
        var installRoot = gitInstallRoot(scope);
        if (installRoot == null) {
            throw new IllegalStateException("Missing git install root");
        }
        return resolveManagedPath(installRoot, source.host(), source.path());
    }

    // ── temporary 检出（:2177-2184）─────────────────────────────

    /** pi {@code getTemporaryDir}：{@code tmp/extensions/<prefix>/<sha256-8>[/<suffix>]}，
     *  hash＝{@code sha256("<prefix>-<suffix>[@<ref>]")} 前 8 位（逐字）。 */
    public Path temporaryDir(String prefix, String suffix, String ref) {
        var root = resolveManagedPath(extensionTempFolder(), prefix);
        var hashInput = prefix + "-" + (suffix == null ? "" : suffix)
            + (ref != null ? "@" + ref : "");
        var hash = sha256Hex(hashInput).substring(0, 8);
        return resolveManagedPath(root, hash, suffix == null ? "" : suffix);
    }

    /** pi {@code getExtensionTempFolder}（:231-235）：{@code agentDir/tmp/extensions}，
     *  {@code 0700} best-effort（POSIX 支持时才设，D109）。 */
    public Path extensionTempFolder() {
        var tempFolder = resolver.agentDir().resolve("tmp").resolve("extensions");
        try {
            Files.createDirectories(tempFolder);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(tempFolder,
                    PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            // best-effort（pi chmod 失败同样不因布局崩；D109）
        }
        return tempFolder;
    }

    /** pi {@code resolveManagedPath}（:2186-2193）越根防护——模型可见文案逐字。 */
    public Path resolveManagedPath(Path root, String... parts) {
        var resolvedRoot = root.toAbsolutePath().normalize();
        var resolvedPath = resolvedRoot;
        for (var part : parts) {
            resolvedPath = resolvedPath.resolve(part);
        }
        resolvedPath = resolvedPath.normalize();
        if (!resolvedPath.equals(resolvedRoot) && !resolvedPath.startsWith(resolvedRoot)) {
            throw new IllegalStateException(
                "Refusing to use path outside package install root: " + resolvedPath);
        }
        return resolvedPath;
    }

    // ── npm 工程脚手架（:2062-2075）──────────────────────────────

    /** pi {@code ensureNpmProject} 逐字：建目录＋{@code .gitignore}＋
     *  {@code package.json {"name":"pi-extensions","private":true}}。
     *  {@code markPathIgnoredByCloudSync} 无对位 ⇒ no-op（D108）。 */
    public void ensureNpmProject(Path installRoot) throws IOException {
        Files.createDirectories(installRoot);
        ensureGitIgnore(installRoot);
        var packageJsonPath = installRoot.resolve("package.json");
        if (!Files.exists(packageJsonPath)) {
            // JSON.stringify(pkgJson, null, 2) 逐字节对位（无尾换行）。
            Files.writeString(packageJsonPath,
                "{\n  \"name\": \"pi-extensions\",\n  \"private\": true\n}");
        }
    }

    /** pi {@code ensureGitIgnore}（:2077-2083）：{@code "*\n!.gitignore\n"} 逐字。 */
    public void ensureGitIgnore(Path dir) throws IOException {
        Files.createDirectories(dir);
        var ignorePath = dir.resolve(".gitignore");
        if (!Files.exists(ignorePath)) {
            Files.writeString(ignorePath, "*\n!.gitignore\n");
        }
    }

    // ── 已装版本读取（:1533-1543 / :1507-1513）───────────────────

    /** pi {@code getInstalledNpmVersion}：读安装目录 {@code package.json} 的
     *  {@code version}（strip BOM，解析失败 ⇒ {@code null}）。 */
    public String installedNpmVersion(Path installedPath) {
        var packageJsonPath = installedPath.resolve("package.json");
        if (!Files.exists(packageJsonPath)) {
            return null;
        }
        try {
            var content = Files.readString(packageJsonPath);
            JsonNode pkg = Json.mapper().readTree(stripBom(content));
            var version = pkg.get("version");
            return version != null && version.isTextual() ? version.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** pi {@code installedNpmMatchesConfiguredVersion}（:1507-1513）：已装版本
     *  不满足配置 range ⇒ 重装。 */
    public boolean installedNpmMatchesConfiguredVersion(NpmSource source, Path installedPath) {
        var installedVersion = installedNpmVersion(installedPath);
        if (installedVersion == null) {
            return false;
        }
        return source.range() == null || Semvers.satisfies(installedVersion, source.range());
    }

    private static String stripBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
