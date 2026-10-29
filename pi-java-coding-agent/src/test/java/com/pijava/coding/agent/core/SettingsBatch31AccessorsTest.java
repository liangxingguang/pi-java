package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 批 31 设置选择器依赖的边界与访问器契约。 */
class SettingsBatch31AccessorsTest {

    @Test
    void quietStartupHeaderSurvivesWriteAndRead() {
        var storage = new InMemorySettingsStorage();
        var manager = SettingsManager.withStorage(storage);

        manager.accessors().setQuietStartupMode("header");
        manager.flush();

        assertThat(storage.readGlobal().quietStartup).isEqualTo("header");
        assertThat(manager.accessors().getQuietStartupMode()).isEqualTo("header");
        assertThat(manager.accessors().getQuietStartup()).isNull();
    }

    @Test
    void enabledModelsDistinguishesUnsetFromExplicitEmpty() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());

        manager.accessors().setEnabledModels(null);
        assertThat(manager.accessors().getEnabledModelsOrNull()).isNull();

        manager.accessors().setEnabledModels(List.of());
        assertThat(manager.accessors().getEnabledModelsOrNull()).isEmpty();
    }

    @Test
    void terminalAndImageWritesPreserveSiblingFields() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());
        manager.global().terminal = new Settings.Terminal(
            true, 80, false, true, "auto", "kitty", false);
        manager.global().images = new Settings.Image(true, false);

        manager.uiPrefs().setClearOnShrink(true);
        manager.compaction().setBlockImages(true);

        assertThat(manager.global().terminal.imageWidthCells()).isEqualTo(80);
        assertThat(manager.global().terminal.showTerminalProgress()).isTrue();
        assertThat(manager.global().images.autoResize()).isTrue();
        assertThat(manager.compaction().getBlockImages()).isTrue();
    }

    @Test
    void networkAndMarkdownDefaultsMatchPi() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());

        assertThat(manager.network().getTransport()).isEqualTo("auto");
        assertThat(manager.compaction().getImageAutoResize()).isTrue();
        assertThat(manager.compaction().getMarkdownMermaid()).isEqualTo("streaming");
        assertThat(manager.uiPrefs().getShowImages()).isTrue();
        assertThat(manager.uiPrefs().getImageWidthCells()).isEqualTo(60);
        assertThat(manager.uiPrefs().getClearOnShrink()).isFalse();
        assertThat(manager.uiPrefs().getShowTerminalProgress()).isFalse();
    }

    @Test
    void newAccessorsWriteEverySelectorValue() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());

        manager.network().setTransport("websocket");
        manager.compaction().setImageAutoResize(false);
        manager.compaction().setMarkdownMermaid("final");
        manager.uiPrefs().setShowImages(false);
        manager.uiPrefs().setImageWidthCells(0);
        manager.uiPrefs().setClearOnShrink(true);
        manager.uiPrefs().setShowTerminalProgress(true);

        assertThat(manager.network().getTransport()).isEqualTo("websocket");
        assertThat(manager.compaction().getImageAutoResize()).isFalse();
        assertThat(manager.compaction().getMarkdownMermaid()).isEqualTo("final");
        assertThat(manager.uiPrefs().getShowImages()).isFalse();
        assertThat(manager.uiPrefs().getImageWidthCells()).isEqualTo(1);
        assertThat(manager.uiPrefs().getClearOnShrink()).isTrue();
        assertThat(manager.uiPrefs().getShowTerminalProgress()).isTrue();
    }
}
