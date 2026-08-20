package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Generic selectable list: up/down selection, Enter confirm, Esc cancel,
 * fuzzy filter (Phase 3 design §8.3). Shared by the model/session/tree
 * selectors.
 */
public final class SelectList<T> {

    private final List<T> allItems;
    private final Function<T, String> label;
    private final List<T> visible = new ArrayList<>();
    private int selected;
    private boolean cancelled;
    private boolean confirmed;
    private String filter = "";

    /**
     * Creates a selectable list over the given items.
     *
     * @param items all items in their canonical order
     * @param label maps an item to its display label
     */
    public SelectList(List<T> items, Function<T, String> label) {
        this.allItems = List.copyOf(items);
        this.label = label;
        refresh();
    }

    /** Handle selection keys; returns true when a key was consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isUp()) {
            selected = Math.max(0, selected - 1);
            return true;
        }
        if (event.isDown()) {
            selected = Math.min(visible.size() - 1, selected + 1);
            return true;
        }
        if (event.isConfirm()) {
            confirmed = true;
            return true; // caller reads selected()
        }
        if (event.isKey(KeyCode.BACKSPACE)) {
            if (filter.isEmpty()) {
                return false;
            }
            filter = filter.substring(0, filter.length() - 1);
            refresh();
            return true;
        }
        if (event.isCancel()) {
            // First Esc clears an active filter; a second Esc cancels.
            if (!filter.isEmpty()) {
                filter = "";
                refresh();
                return true;
            }
            cancelled = true;
            return true;
        }
        if (isFilterInput(event)) {
            filter += event.character();
            refresh();
            return true;
        }
        return false;
    }

    /** Apply a fuzzy filter; empty query shows all items. */
    public void filter(String query) {
        this.filter = query == null ? "" : query;
        refresh();
    }

    /** The current filter query (P6-24 type-to-filter; empty when inactive). */
    public String filter() {
        return filter;
    }

    /** Printable character without Ctrl/Alt — accumulates into the filter. */
    private static boolean isFilterInput(KeyEvent event) {
        if (event.hasCtrl() || event.hasAlt()) {
            return false;
        }
        return event.code() == KeyCode.CHAR
            && !Character.isISOControl(event.character());
    }

    /** The currently selected item, if any. */
    public Optional<T> selected() {
        if (cancelled || visible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(visible.get(Math.min(selected, visible.size() - 1)));
    }

    /** Whether the user cancelled the selection (Esc). */
    public boolean cancelled() {
        return cancelled;
    }

    /** Whether the user confirmed a selection (Enter). */
    public boolean confirmed() {
        return confirmed;
    }

    /** Reset selection state (e.g. when reopening the selector). */
    public void reset() {
        selected = 0;
        cancelled = false;
        confirmed = false;
    }

    /** Render the list with the current selection highlighted. */
    public Element render() {
        String title = filter.isEmpty()
            ? "Select (type to filter, ↑/↓ enter, Esc cancel)"
            : "Filter: \"" + filter + "\" (Esc clear, enter confirm)";
        return TamboUIAdapter.list(
            visible.stream().map(label).toList())
            .selected(Math.min(selected, Math.max(0, visible.size() - 1)))
            .highlightColor(dev.tamboui.style.Color.CYAN)
            .rounded()
            .title(title)
            .fill();
    }

    private void refresh() {
        visible.clear();
        visible.addAll(FuzzyMatcher.rank(filter, allItems, label));
        selected = 0;
    }
}
