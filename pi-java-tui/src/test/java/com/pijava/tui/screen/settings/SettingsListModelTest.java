package com.pijava.tui.screen.settings;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsListModelTest {

    @Test
    void searchWrapAndValueOutsideOptionsFollowSettingsList() {
        var items = List.of(
            SettingsItem.cycle("a", "Alpha", "", "unknown", List.of("one", "two")),
            SettingsItem.cycle("b", "Beta", "", "off", List.of("off", "on")));
        var model = new SettingsListModel(items, 10);

        model.move(-1);
        assertThat(model.selected().id()).isEqualTo("b");

        model.search("alp");
        assertThat(model.selected().id()).isEqualTo("a");
        var activation = model.activate();

        assertThat(activation).isInstanceOf(SettingsListModel.Activation.Cycle.class);
        assertThat(((SettingsListModel.Activation.Cycle) activation).newValue()).isEqualTo("one");
        assertThat(model.selected().currentValue()).isEqualTo("one");
    }

    @Test
    void searchMatchesLabelButNotDescription() {
        var model = new SettingsListModel(List.of(
            SettingsItem.cycle("a", "Alpha", "needle in description", "off", List.of("off"))), 10);

        model.search("needle");

        assertThat(model.filteredItems()).isEmpty();
        assertThat(model.selected()).isNull();
        assertThat(model.activate()).isNull();
    }

    @Test
    void visibleRangeCentersSelectionAndReportsScrollPosition() {
        var items = new java.util.ArrayList<SettingsItem>();
        for (int i = 0; i < 15; i++) {
            items.add(SettingsItem.cycle("i" + i, "Item " + i, "", "off", List.of("off")));
        }
        var model = new SettingsListModel(items, 10);
        for (int i = 0; i < 14; i++) {
            model.move(1);
        }

        assertThat(model.visibleItems()).hasSize(10);
        assertThat(model.visibleItems().getLast().id()).isEqualTo("i14");
        assertThat(model.scrollPosition()).isEqualTo("  (15/15)");
    }

    @Test
    void submenuActivationCarriesTypeAndDoesNotCycle() {
        var model = new SettingsListModel(List.of(new SettingsItem(
            "warnings", "Warnings", "", "configure", List.of(), SettingsItem.Submenu.WARNINGS)), 10);

        assertThat(model.activate()).isEqualTo(
            new SettingsListModel.Activation.OpenSubmenu("warnings", SettingsItem.Submenu.WARNINGS));
        assertThat(model.selected().currentValue()).isEqualTo("configure");
    }
}
