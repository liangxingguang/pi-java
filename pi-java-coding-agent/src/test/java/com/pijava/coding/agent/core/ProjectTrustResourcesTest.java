package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 23（docs/34 T3）：{@link ProjectTrustResources}——pi
 * {@code hasTrustRequiringProjectResources}（trust-manager.ts:186-208）：
 * 8 条目存在性＋祖先链 {@code .agents/skills}（HOME 豁免）。
 */
class ProjectTrustResourcesTest {

    @TempDir
    Path cwd;

    @Test
    void emptyProjectIsNotTrustRequiring() {
        assertThat(ProjectTrustResources.hasTrustRequiringProjectResources(cwd)).isFalse();
    }

    @Test
    void settingsJsonTriggers() throws Exception {
        var dir = Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(dir.resolve("settings.json"), "{}");
        assertThat(ProjectTrustResources.hasTrustRequiringProjectResources(cwd)).isTrue();
    }

    @Test
    void systemMdTriggers() throws Exception {
        var dir = Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(dir.resolve("SYSTEM.md"), "sys");
        assertThat(ProjectTrustResources.hasTrustRequiringProjectResources(cwd)).isTrue();
    }

    @Test
    void extensionsDirectoryTriggers() throws Exception {
        Files.createDirectories(cwd.resolve(".pi-java").resolve("extensions"));
        assertThat(ProjectTrustResources.hasTrustRequiringProjectResources(cwd)).isTrue();
    }

    @Test
    void unrelatedEntriesDoNotTrigger() throws Exception {
        var dir = Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(dir.resolve("README.md"), "x"); // 不在 8 条目清单
        Files.createDirectories(cwd.resolve(".pi-java").resolve("sessions"));
        assertThat(ProjectTrustResources.hasTrustRequiringProjectResources(cwd)).isFalse();
    }

    @Test
    void ancestorAgentsSkillsTriggers() throws Exception {
        var ancestor = Files.createDirectories(cwd.resolve(".agents").resolve("skills"));
        var child = Files.createDirectories(cwd.resolve("sub").resolve("proj"));
        assertThat(ProjectTrustResources.hasTrustRequiringProjectResources(child)).isTrue();
    }
}
