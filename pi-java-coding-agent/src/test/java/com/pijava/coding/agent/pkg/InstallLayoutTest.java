package com.pijava.coding.agent.pkg;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * 批 18 T3（docs/29 §3）：pi 安装布局面——安装根三档/temporary 哈希检出/
 * 越根防护/{@code ensureNpmProject} 逐字节/已装版本读取与一致性门。
 */
class InstallLayoutTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    private InstallLayout layout() {
        return new InstallLayout(new SourceResolver(cwd, agentDir, () -> true));
    }

    @Test
    void installRootsFollowScope() {
        var layout = layout();

        assertThat(layout.npmInstallRoot(SourceScope.USER, false))
            .isEqualTo(agentDir.resolve("npm"));
        assertThat(layout.npmInstallRoot(SourceScope.PROJECT, false))
            .isEqualTo(cwd.resolve(".pi-java").resolve("npm"));
        assertThat(layout.gitInstallRoot(SourceScope.USER)).isEqualTo(agentDir.resolve("git"));
        assertThat(layout.gitInstallRoot(SourceScope.PROJECT))
            .isEqualTo(cwd.resolve(".pi-java").resolve("git"));
        assertThat(layout.gitInstallRoot(SourceScope.TEMPORARY)).isNull();
    }

    @Test
    void projectRootRequiresTrust() {
        var untrusted = new InstallLayout(new SourceResolver(cwd, agentDir, () -> false));

        assertThatThrownBy(() -> untrusted.npmInstallRoot(SourceScope.PROJECT, false))
            .hasMessage("Project is not trusted; refusing to access project package storage");
    }

    @Test
    void managedNpmPathNestsScopedNames() {
        var source = new NpmSource("@org/pkg@1.0.0", "@org/pkg", "1.0.0", "1.0.0", true);

        assertThat(layout().managedNpmInstallPath(source, SourceScope.USER))
            .isEqualTo(agentDir.resolve("npm").resolve("node_modules/@org/pkg"));
    }

    @Test
    void temporaryDirUsesSha256EightCharHash() {
        var layout = layout();
        var base = agentDir.resolve("tmp").resolve("extensions");

        // pi :2177-2184 哈希公式钉死（sha256("<prefix>-<suffix>[@<ref>]") 前 8 位）。
        assertThat(layout.temporaryDir("npm", null, null))
            .isEqualTo(base.resolve("npm").resolve("f35b2129"));
        assertThat(layout.temporaryDir("git-github.com", "u/r", "v1"))
            .isEqualTo(base.resolve("git-github.com").resolve("8dd28ea3").resolve("u/r"));
        assertThat(layout.temporaryDir("git-github.com", "u/r", null))
            .isEqualTo(base.resolve("git-github.com").resolve("7d6b5207").resolve("u/r"));
    }

    @Test
    void gitInstallPathNestsHostAndPath() {
        var git = new GitSource("https://github.com/u/r", "github.com", "u/r", null, false);

        assertThat(layout().gitInstallPath(git, SourceScope.USER))
            .isEqualTo(agentDir.resolve("git").resolve("github.com").resolve("u/r"));
    }

    @Test
    void resolveManagedPathRefusesEscapeVerbatim() {
        var layout = layout();
        var root = agentDir.resolve("npm");

        // pi :2186-2193 模型可见文案逐字。
        assertThatThrownBy(() -> layout.resolveManagedPath(root, "..", "..", "escape"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Refusing to use path outside package install root: ");
    }

    @Test
    void ensureNpmProjectWritesExactBytes() throws Exception {
        var root = cwd.resolve("npmroot");
        layout().ensureNpmProject(root);

        // JSON.stringify(pkg, null, 2) 与 "*\n!.gitignore\n" 逐字节对位。
        assertThat(Files.readString(root.resolve("package.json")))
            .isEqualTo("{\n  \"name\": \"pi-extensions\",\n  \"private\": true\n}");
        assertThat(Files.readString(root.resolve(".gitignore")))
            .isEqualTo("*\n!.gitignore\n");
        // 幂等：已存在不改写。
        Files.writeString(root.resolve("package.json"), "{\"custom\":true}");
        layout().ensureNpmProject(root);
        assertThat(Files.readString(root.resolve("package.json"))).isEqualTo("{\"custom\":true}");
    }

    @Test
    void installedNpmVersionReadsPackageJson() throws Exception {
        var dir = Files.createDirectories(cwd.resolve("inst"));
        Files.writeString(dir.resolve("package.json"), "﻿{\"name\":\"x\",\"version\":\"1.2.3\"}");

        assertThat(layout().installedNpmVersion(dir)).isEqualTo("1.2.3");
    }

    @Test
    void installedNpmVersionToleratesGarbage() throws Exception {
        var dir = Files.createDirectories(cwd.resolve("garbage"));

        assertThat(layout().installedNpmVersion(dir)).isNull();
        Files.writeString(dir.resolve("package.json"), "not json{{{");
        assertThat(layout().installedNpmVersion(dir)).isNull();
    }

    @Test
    void installedMatchesConfiguredVersionGate() throws Exception {
        var layout = layout();
        var dir = Files.createDirectories(cwd.resolve("gated"));
        Files.writeString(dir.resolve("package.json"), "{\"version\":\"1.5.0\"}");

        var ranged = new NpmSource("x@^1.0.0", "x", "^1.0.0", "^1.0.0", false);
        var unranged = new NpmSource("x", "x", null, null, false);

        assertThat(layout.installedNpmMatchesConfiguredVersion(ranged, dir)).isTrue();
        assertThat(layout.installedNpmMatchesConfiguredVersion(
            new NpmSource("x@^2.0.0", "x", "^2.0.0", "^2.0.0", false), dir)).isFalse();
        assertThat(layout.installedNpmMatchesConfiguredVersion(unranged, dir)).isTrue();
        assertThat(layout.installedNpmMatchesConfiguredVersion(unranged, cwd)).isFalse();
    }

    @Test
    void offlineFlagTruthTable() {
        assertThat(PackageEnvironment.isOfflineModeEnabled("1")).isTrue();
        assertThat(PackageEnvironment.isOfflineModeEnabled("TRUE")).isTrue();
        assertThat(PackageEnvironment.isOfflineModeEnabled("yes")).isTrue();
        assertThat(PackageEnvironment.isOfflineModeEnabled("0")).isFalse();
        assertThat(PackageEnvironment.isOfflineModeEnabled("no")).isFalse();
        assertThat(PackageEnvironment.isOfflineModeEnabled(null)).isFalse();
        assertThat(PackageEnvironment.isOfflineModeEnabled("")).isFalse();
    }
}
