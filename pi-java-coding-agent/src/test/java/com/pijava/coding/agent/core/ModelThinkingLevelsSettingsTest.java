package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 11 T2（docs/22 §3.1，pi {@code settings-manager.ts:768-793}）：
 * {@code modelThinkingLevels} 四访问器——get（合并视图查 {@code provider/modelId}）、
 * getAll（缺席 ⇒ 空表浅拷贝）、set（写全局域建表）、remove（删空后整键抹除）。
 */
class ModelThinkingLevelsSettingsTest {

    private static FileSettingsStorage storage(Path dir) {
        return new FileSettingsStorage(dir, dir);
    }

    /** 缺席 ⇒ get 为 null、getAll 为空表。 */
    @Test
    void absentResolvesToNullAndEmptyMap(@TempDir Path dir) {
        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getModelThinkingLevel("anthropic", "claude")).isNull();
        assertThat(accessors.getAllModelThinkingLevels()).isEmpty();
    }

    /** set 建表并落全局域（flush 后重读得见）；get 按 {@code provider/modelId} 命中。 */
    @Test
    void setCreatesTableAndRoundTrips(@TempDir Path dir) {
        var manager = SettingsManager.withStorage(storage(dir));

        manager.accessors().setModelThinkingLevel("anthropic", "claude", "high");
        manager.flush();

        var reloaded = SettingsManager.withStorage(storage(dir)).accessors();
        assertThat(reloaded.getModelThinkingLevel("anthropic", "claude")).isEqualTo("high");
        assertThat(reloaded.getAllModelThinkingLevels())
            .isEqualTo(Map.of("anthropic/claude", "high"));
    }

    /** remove 删空后整键抹除（JSON 不出空表，pi :790-792）。 */
    @Test
    void removeErasesWholeKeyWhenEmptied(@TempDir Path dir) throws Exception {
        var manager = SettingsManager.withStorage(storage(dir));
        manager.accessors().setModelThinkingLevel("anthropic", "claude", "high");
        manager.accessors().removeModelThinkingLevel("anthropic", "claude");
        manager.flush();

        // java JSON 边界惯例＝top-level 字段缺席即显式 null（全字段皆然）；钉的是
        // 「不留空表 {}」（pi 的 delete 对位在 java 是 null 值）。
        var json = Files.readString(dir.resolve("settings.json"));
        assertThat(json).contains("\"modelThinkingLevels\" : null");
        assertThat(SettingsManager.withStorage(storage(dir)).accessors()
            .getAllModelThinkingLevels()).isEmpty();
        // 空表上 remove 是 noop（pi :786 的早退）。
        manager.accessors().removeModelThinkingLevel("anthropic", "claude");
    }

    /** 合并视图：项目域同键覆盖全局域（deepMerge 对象递归，pi 的合并视图读法）。 */
    @Test
    void projectScopeOverridesGlobalPerKey(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),
            "{\"modelThinkingLevels\":{\"anthropic/claude\":\"low\",\"google/gemini\":\"high\"}}");
        Files.createDirectories(dir.resolve(".pi-java"));
        Files.writeString(dir.resolve(".pi-java").resolve("settings.json"),
            "{\"modelThinkingLevels\":{\"anthropic/claude\":\"max\"}}");

        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getModelThinkingLevel("anthropic", "claude")).isEqualTo("max");
        assertThat(accessors.getModelThinkingLevel("google", "gemini")).isEqualTo("high");
        assertThat(accessors.getAllModelThinkingLevels()).containsExactlyInAnyOrderEntriesOf(
            Map.of("anthropic/claude", "max", "google/gemini", "high"));
    }
}
