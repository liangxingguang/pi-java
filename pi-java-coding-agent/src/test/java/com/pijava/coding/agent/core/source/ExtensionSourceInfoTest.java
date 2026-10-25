package com.pijava.coding.agent.core.source;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 23（docs/34 T4，E5-34）：扩展路径 sourceInfo 四规则——
 * pi {@code getDefaultSourceInfoForPath}（resource-loader.ts:978-1024）＋
 * {@code getSyntheticPathSource}（source-info.ts:19-24）。
 */
class ExtensionSourceInfoTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path projectDir;

    @Test
    void builtinPathIsSyntheticTemporary() {
        var info = ExtensionSourceInfos.forExtensionPath("builtin:mcp", agentDir, projectDir);
        assertThat(info.path()).isEqualTo("builtin:mcp");
        assertThat(info.source()).isEqualTo("builtin");
        assertThat(info.scope()).isEqualTo(SourceInfo.Scope.TEMPORARY);
        assertThat(info.origin()).isEqualTo(SourceInfo.Origin.TOP_LEVEL);
        assertThat(info.baseDir()).isNull();
    }

    @Test
    void inlinePathTakesAnglePrefix() {
        var info = ExtensionSourceInfos.forExtensionPath("<inline:demo>", null, null);
        assertThat(info.source()).isEqualTo("inline");
        assertThat(info.scope()).isEqualTo(SourceInfo.Scope.TEMPORARY);
    }

    @Test
    void underAgentExtensionsRootIsUserScope() {
        var jar = agentDir.resolve("extensions").resolve("demo.jar");
        var info = ExtensionSourceInfos.forExtensionPath(jar.toString(), agentDir, projectDir);
        assertThat(info.scope()).isEqualTo(SourceInfo.Scope.USER);
        assertThat(info.source()).isEqualTo("local");
        assertThat(info.baseDir()).endsWith("extensions");
    }

    @Test
    void underProjectExtensionsRootIsProjectScope() throws Exception {
        var root = projectDir.resolve(".pi-java").resolve("extensions");
        Files.createDirectories(root);
        var jar = root.resolve("demo.jar");
        Files.writeString(jar, "x");
        var info = ExtensionSourceInfos.forExtensionPath(jar.toString(), agentDir, projectDir);
        assertThat(info.scope()).isEqualTo(SourceInfo.Scope.PROJECT);
        assertThat(info.baseDir()).isEqualTo(root.toAbsolutePath().normalize().toString());
    }

    @Test
    void elsewhereIsTemporaryWithParentBaseDir() throws Exception {
        var elsewhere = Files.createTempDirectory("elsewhere").resolve("demo.jar");
        Files.writeString(elsewhere, "x");
        var info = ExtensionSourceInfos.forExtensionPath(elsewhere.toString(), agentDir, projectDir);
        assertThat(info.scope()).isEqualTo(SourceInfo.Scope.TEMPORARY);
        // pi statSync 臂：文件 ⇒ baseDir＝父目录。
        assertThat(info.baseDir()).isEqualTo(elsewhere.getParent().toString());
    }

    @Test
    void nullRootsSkipScopeRoots() {
        var info = ExtensionSourceInfos.forExtensionPath("builtin:x", null, null);
        assertThat(info.source()).isEqualTo("builtin");
    }
}
