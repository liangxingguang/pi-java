package com.pijava.coding.agent.pkg;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.pkg.SourceResolver.ScopedPackage;

/**
 * 批 18 T2（docs/29 §3）：pi 身份/查重/作用域面——{@code getPackageIdentity}
 *（:1721-1735）、{@code dedupePackages}（:1742-1763）、{@code packageSourcesMatch}
 *（:1463-1467）、信任门（:1775-1779）。
 */
class SourceResolverTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    private SourceResolver trusted() {
        return new SourceResolver(cwd, agentDir, () -> true);
    }

    @Test
    void npmIdentityIgnoresVersion() {
        var resolver = trusted();

        assertThat(resolver.getPackageIdentity("npm:foo@1.0.0", SourceScope.USER))
            .isEqualTo("npm:foo");
        assertThat(resolver.getPackageIdentity("npm:@org/foo@^2", SourceScope.PROJECT))
            .isEqualTo("npm:@org/foo");
    }

    @Test
    void gitIdentityNormalizesSshAndHttps() {
        var resolver = trusted();

        // scp-like 须带 git: 前缀才是 git 源（裸写被 isLocalPath 判本地）。
        var ssh = resolver.getPackageIdentity("git:git@github.com:u/r", SourceScope.USER);
        var https = resolver.getPackageIdentity("https://github.com/u/r@v1", SourceScope.USER);

        assertThat(ssh).isEqualTo("git:github.com/u/r");
        assertThat(ssh).isEqualTo(https);
    }

    @Test
    void localIdentityResolvesAgainstScopeBaseDir() {
        var resolver = trusted();

        var scoped = resolver.getPackageIdentity("./pkg", SourceScope.USER);
        var unscoped = resolver.getPackageIdentity("./pkg", null);

        assertThat(scoped).isEqualTo("local:" + agentDir.resolve("pkg"));
        assertThat(unscoped).isEqualTo("local:" + cwd.resolve("pkg"));
    }

    @Test
    void dedupeProjectWinsOverUser() {
        var resolver = trusted();
        var user = new ScopedPackage(PackageSource.of("npm:foo@1.0.0"), SourceScope.USER);
        var project = new ScopedPackage(PackageSource.of("npm:foo@2.0.0"), SourceScope.PROJECT);

        var deduped = resolver.dedupePackages(List.of(user, project));

        assertThat(deduped).containsExactly(project);
    }

    @Test
    void dedupeAutoloadFalseProjectDeltaKeepsBoth() {
        // pi :1754-1756：project autoload=false 是 user 条目的 delta ⇒ 双留。
        var resolver = trusted();
        var user = new ScopedPackage(PackageSource.of("npm:foo"), SourceScope.USER);
        var delta = new ScopedPackage(
            PackageSource.object("npm:foo", false, null, List.of("extra.md"), null, null),
            SourceScope.PROJECT);

        var deduped = resolver.dedupePackages(List.of(delta, user));

        assertThat(deduped).containsExactly(delta, user);
    }

    @Test
    void dedupeDifferentIdentitiesAllKept() {
        var resolver = trusted();
        var a = new ScopedPackage(PackageSource.of("npm:a"), SourceScope.USER);
        var b = new ScopedPackage(PackageSource.of("npm:b"), SourceScope.USER);

        assertThat(resolver.dedupePackages(List.of(a, b))).containsExactly(a, b);
    }

    @Test
    void packageSourcesMatchByIdentity() {
        var resolver = trusted();

        assertThat(resolver.packageSourcesMatch(
            PackageSource.object("npm:foo@1.0.0", null, null, null, null, null),
            "npm:foo@9.9.9", SourceScope.USER)).isTrue();
        assertThat(resolver.packageSourcesMatch(
            PackageSource.of("npm:foo"), "npm:bar", SourceScope.USER)).isFalse();
    }

    @Test
    void packageSourcesMatchLocalAgainstScopeBase() {
        var resolver = trusted();
        var inBase = agentDir.resolve("pkg").toString();

        assertThat(resolver.packageSourcesMatch(
            PackageSource.of("./pkg"), inBase, SourceScope.USER)).isTrue();
    }

    @Test
    void normalizeLocalSourceRelativeToScopeBase() {
        var resolver = trusted();
        var inBase = cwd.resolve(SourceResolver.CONFIG_DIR_NAME).resolve("pkg").toString();

        var normalized = resolver.normalizePackageSourceForSettings(inBase, SourceScope.PROJECT);

        assertThat(normalized).isEqualTo("pkg");
        // 非 local 源原样。
        assertThat(resolver.normalizePackageSourceForSettings("npm:foo@1", SourceScope.USER))
            .isEqualTo("npm:foo@1");
    }

    @Test
    void projectScopeRequiresTrust() {
        var untrusted = new SourceResolver(cwd, agentDir, () -> false);

        // pi :1775-1779 模型可见文案逐字。
        assertThatThrownBy(() -> untrusted.getBaseDirForScope(SourceScope.PROJECT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Project is not trusted; refusing to access project package storage");
        assertThat(trusted().getBaseDirForScope(SourceScope.PROJECT))
            .isEqualTo(cwd.resolve(".pi-java"));
        assertThat(trusted().getBaseDirForScope(SourceScope.USER)).isEqualTo(agentDir);
        assertThat(trusted().getBaseDirForScope(SourceScope.TEMPORARY)).isEqualTo(cwd);
    }

    @Test
    void resolvePathExpandsTildeAndResolvesRelative() {
        var resolver = trusted();
        var home = System.getProperty("user.home");

        assertThat(resolver.resolvePath("~/x")).isEqualTo(Path.of(home, "x").toString());
        assertThat(resolver.resolvePath("./y")).isEqualTo(cwd.resolve("y").toString());
    }
}
