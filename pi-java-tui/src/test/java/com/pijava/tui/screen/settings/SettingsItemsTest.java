package com.pijava.tui.screen.settings;

import java.util.List;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.SettingsManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsItemsTest {

    @Test
    void unsupportedImagesUsesExactPiOrderAndCompleteIdSet() {
        var items = build(false);

        assertThat(items).extracting(SettingsItem::id).startsWith(
            "autocompact", "auto-resize-images", "block-images", "skill-commands",
            "show-hardware-cursor", "editor-padding", "output-padding",
            "autocomplete-max-visible", "clear-on-shrink", "terminal-progress",
            "steering-mode");
        assertThat(items).extracting(SettingsItem::id).containsExactlyInAnyOrder(
            "autocompact", "auto-resize-images", "block-images", "skill-commands",
            "show-hardware-cursor", "editor-padding", "output-padding",
            "autocomplete-max-visible", "clear-on-shrink", "terminal-progress",
            "steering-mode", "follow-up-mode", "transport", "share-destination",
            "http-idle-timeout",
            "cache-warming-mode", "hide-thinking", "mermaid-rendering",
            "cache-miss-notices", "collapse-changelog", "quiet-startup",
            "install-telemetry", "default-project-trust", "double-escape-action",
            "tree-filter-mode", "warnings", "model-thinking", "tui-mode",
            "fullscreen-exit-output", "fullscreen-scrollbar", "fullscreen-copy-on-select",
            "fullscreen-wheel-scroll-lines", "theme");
        assertThat(items).extracting(SettingsItem::id)
            .doesNotContain("show-images", "image-width-cells");
    }

    @Test
    void supportedImagesInsertsBothRowsImmediatelyAfterAutocompact() {
        assertThat(build(true)).extracting(SettingsItem::id).startsWith(
            "autocompact", "show-images", "image-width-cells", "auto-resize-images", "block-images");
    }

    @Test
    void specialValuesAndSubmenusMatchPi() {
        var items = build(false);

        assertThat(item(items, "quiet-startup").values()).containsExactly("true", "header", "false");
        assertThat(item(items, "default-project-trust").values())
            .containsExactly("Ask", "Always trust", "Never trust");
        assertThat(item(items, "default-project-trust").currentValue()).isEqualTo("Ask");
        assertThat(item(items, "http-idle-timeout").values())
            .containsExactly("30 sec", "1 min", "2 min", "5 min", "disabled");
        assertThat(item(items, "http-idle-timeout").currentValue()).isEqualTo("5 min");
        assertThat(item(items, "fullscreen-wheel-scroll-lines").values())
            .containsExactly("auto", "1", "2", "3", "5", "10");
        assertThat(item(items, "warnings").submenu()).isEqualTo(SettingsItem.Submenu.WARNINGS);
        assertThat(item(items, "model-thinking").submenu())
            .isEqualTo(SettingsItem.Submenu.MODEL_THINKING);
        assertThat(item(items, "theme").submenu()).isEqualTo(SettingsItem.Submenu.THEME);
    }

    private static SettingsItem item(List<SettingsItem> items, String id) {
        return items.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<SettingsItem> build(boolean images) {
        var settings = SettingsManager.withStorage(new InMemorySettingsStorage());
        return SettingsItems.build(settings, ModelCatalog.empty(), null,
            "system", List.of("system"), images, "Alt+Enter", "Shift+Tab");
    }
}
