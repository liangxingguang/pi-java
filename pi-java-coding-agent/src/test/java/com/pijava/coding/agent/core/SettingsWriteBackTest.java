package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 19 T2（docs/30 §3/§7）：SettingsManager 写回面——字段级 merge 落盘
 * （未知键保留钉死）、{@code PackageSource} 写回不胀形、project 臂信任门、
 * 两写不丢、null 删键；以及 {@code setProjectTrusted(false)} 不抹盘的修真回归。
 */
class SettingsWriteBackTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path projectDir;

    private FileSettingsStorage storage;
    private SettingsManager manager;

    @BeforeEach
    void setUp() {
        storage = new FileSettingsStorage(agentDir, projectDir);
        manager = SettingsManager.withStorage(storage);
    }

    private Path globalFile() {
        return agentDir.resolve("settings.json");
    }

    private Path projectFile() {
        return projectDir.resolve(".pi-java").resolve("settings.json");
    }

    private String readGlobalJson() throws IOException {
        return Files.readString(globalFile());
    }

    @Test
    void fieldLevelMergePreservesUnknownKeys() throws IOException {
        Files.writeString(globalFile(),
            "{\"futureKey\":{\"a\":1},\"theme\":\"dark\",\"packages\":[\"npm:old/pkg\"]}");
        manager.reload();

        manager.setPackages(List.of(PackageSource.of("npm:new/pkg")));

        var json = readGlobalJson();
        assertThat(json).contains("\"futureKey\"").contains("\"theme\" : \"dark\"");
        assertThat(json).contains("\"npm:new/pkg\"").doesNotContain("npm:old/pkg");
        // 内存视图同步
        assertThat(manager.global().packages).containsExactly(PackageSource.of("npm:new/pkg"));
    }

    @Test
    void bareStringWritesBackBareStringAndObjectStaysObject() throws IOException {
        manager.setPackages(List.of(
            PackageSource.of("npm:bare/pkg"),
            PackageSource.object("git:github.com/u/r", null, List.of("+ext/a"), null, null, null)));

        var json = readGlobalJson();
        // 裸串不胀形；对象形只写非 null 键
        assertThat(json).contains("\"npm:bare/pkg\"");
        assertThat(json).contains("\"source\" : \"git:github.com/u/r\"");
        assertThat(json).contains("\"+ext/a\"");
        assertThat(json).doesNotContain("\"autoload\"").doesNotContain("\"skills\"");
    }

    @Test
    void nullValueRemovesKey() throws IOException {
        Files.writeString(globalFile(), "{\"extensions\":[\"a\",\"b\"],\"theme\":\"dark\"}");
        manager.reload();

        manager.setExtensions(null);

        var json = readGlobalJson();
        assertThat(json).doesNotContain("extensions").contains("\"theme\"");
    }

    @Test
    void projectScopeWritesProjectFile() throws IOException {
        manager.setProjectSkills(List.of("+skills/x"));

        assertThat(projectFile()).exists();
        assertThat(Files.readString(projectFile())).contains("+skills/x");
        assertThat(manager.project().skills).containsExactly("+skills/x");
    }

    @Test
    void untrustedProjectWriteRefused() {
        // 模型可见文案逐字（pi settings-manager.ts:554）。
        manager.setProjectTrusted(false);

        assertThatThrownBy(() -> manager.setProjectPackages(List.of(PackageSource.of("npm:x/y"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Project is not trusted; refusing to write project settings");
    }

    @Test
    void untrustingDoesNotWipeProjectFile() throws IOException {
        // 批 19 修真回归：pi setProjectTrusted 只清内存（settings-manager.ts:483）。
        Files.createDirectories(projectFile().getParent());
        Files.writeString(projectFile(), "{\"skills\":[\"keep/me\"]}");
        manager.reload();

        manager.setProjectTrusted(false);

        assertThat(Files.readString(projectFile())).contains("keep/me");
        assertThat(manager.project().skills).isNull();   // 内存视图已清
        assertThat(manager.effective().skills).isNull();
    }

    @Test
    void reTrustingReloadsProjectScope() throws IOException {
        Files.createDirectories(projectFile().getParent());
        Files.writeString(projectFile(), "{\"skills\":[\"keep/me\"]}");
        manager.reload();
        manager.setProjectTrusted(false);

        manager.setProjectTrusted(true);

        assertThat(manager.project().skills).containsExactly("keep/me");
    }

    @Test
    void twoManagersDoNotClobberEachOther() throws IOException {
        Files.writeString(globalFile(), "{\"theme\":\"dark\"}");
        var first = SettingsManager.withStorage(storage);
        var second = SettingsManager.withStorage(storage);

        first.setPackages(List.of(PackageSource.of("npm:a/b")));
        second.setThemes(List.of("light"));

        var json = readGlobalJson();
        assertThat(json).contains("npm:a/b").contains("light").contains("\"theme\"");
    }

    @Test
    void inMemoryStorageRoundTripsMerge() {
        var memory = new InMemorySettingsStorage();
        var initial = new Settings();
        initial.setUnknown("futureKey", 42);
        initial.packages = List.of(PackageSource.of("npm:old/pkg"));
        memory.writeGlobal(initial);
        var mem = SettingsManager.withStorage(memory);

        mem.setPackages(List.of(PackageSource.of("npm:new/pkg")));

        var stored = memory.readGlobal();
        assertThat(stored.packages).containsExactly(PackageSource.of("npm:new/pkg"));
        assertThat(stored.unknown()).containsEntry("futureKey", 42);
    }
}
