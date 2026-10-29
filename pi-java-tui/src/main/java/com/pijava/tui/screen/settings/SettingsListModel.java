package com.pijava.tui.screen.settings;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.component.FuzzyMatch;

/** Keyboard/search state for pi's SettingsList (settings-list.ts:38-328). */
public final class SettingsListModel {

    /** Result of Enter/Space activation. */
    public sealed interface Activation {
        record Cycle(String id, String newValue) implements Activation {}
        record OpenSubmenu(String id, SettingsItem.Submenu submenu) implements Activation {}
    }

    private final List<SettingsItem> items;
    private final int maxVisible;
    private List<SettingsItem> filteredItems;
    private int selectedIndex;
    private String query = "";

    /** Create a list with an independent mutable item table. */
    public SettingsListModel(List<SettingsItem> items, int maxVisible) {
        this.items = new ArrayList<>(items);
        this.filteredItems = List.copyOf(items);
        this.maxVisible = maxVisible;
    }

    /** Move selection with pi's wrap-around navigation. */
    public void move(int delta) {
        if (filteredItems.isEmpty()) {
            return;
        }
        selectedIndex = Math.floorMod(selectedIndex + delta, filteredItems.size());
    }

    /** Filter labels only and reset selection to the first match. */
    public void search(String query) {
        this.query = query == null ? "" : query;
        if (this.query.isEmpty()) {
            filteredItems = List.copyOf(items);
        } else {
            filteredItems = items.stream()
                .filter(item -> FuzzyMatch.fuzzyMatch(this.query, item.label()).matches())
                .toList();
        }
        selectedIndex = 0;
    }

    /** Select a row by id in the current filtered list; missing ids are ignored. */
    public void select(String id) {
        for (int i = 0; i < filteredItems.size(); i++) {
            if (filteredItems.get(i).id().equals(id)) {
                selectedIndex = i;
                return;
            }
        }
    }

    /** Cycle the selected value or request its submenu. */
    public Activation activate() {
        var item = selected();
        if (item == null) {
            return null;
        }
        if (item.submenu() != SettingsItem.Submenu.NONE) {
            return new Activation.OpenSubmenu(item.id(), item.submenu());
        }
        if (item.values().isEmpty()) {
            return null;
        }
        var next = item.values().get((item.values().indexOf(item.currentValue()) + 1) % item.values().size());
        replace(item.withValue(next));
        return new Activation.Cycle(item.id(), next);
    }

    /** Update one row after a submenu or host callback. */
    public void updateValue(String id, String value) {
        items.stream().filter(item -> item.id().equals(id)).findFirst()
            .ifPresent(item -> replace(item.withValue(value)));
    }

    private void replace(SettingsItem replacement) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(replacement.id())) {
                items.set(i, replacement);
                break;
            }
        }
        for (int i = 0; i < filteredItems.size(); i++) {
            if (filteredItems.get(i).id().equals(replacement.id())) {
                var copy = new ArrayList<>(filteredItems);
                copy.set(i, replacement);
                filteredItems = List.copyOf(copy);
                break;
            }
        }
    }

    /** Current row, or null when the filter has no matches. */
    public SettingsItem selected() {
        return selectedIndex >= 0 && selectedIndex < filteredItems.size()
            ? filteredItems.get(selectedIndex) : null;
    }

    /** Current row id, or null when there is no selection. */
    public String selectedId() {
        var item = selected();
        return item == null ? null : item.id();
    }

    /** Current filtered rows in display order. */
    public List<SettingsItem> filteredItems() {
        return filteredItems;
    }

    /** Current search text. */
    public String query() {
        return query;
    }

    /** At most maxVisible rows, centered around selection like pi. */
    public List<SettingsItem> visibleItems() {
        int start = visibleStart();
        int end = Math.min(start + maxVisible, filteredItems.size());
        return filteredItems.subList(start, end);
    }

    private int visibleStart() {
        return Math.max(0, Math.min(selectedIndex - maxVisible / 2, filteredItems.size() - maxVisible));
    }

    /** Scroll indicator, or empty when all rows fit. */
    public String scrollPosition() {
        int start = visibleStart();
        int end = Math.min(start + maxVisible, filteredItems.size());
        return start > 0 || end < filteredItems.size()
            ? "  (" + (selectedIndex + 1) + "/" + filteredItems.size() + ")" : "";
    }
}
