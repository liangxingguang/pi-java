package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
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
        if (event.isCancel()) {
            cancelled = true;
            return true;
        }
        return false;
    }

    /** Apply a fuzzy filter; empty query shows all items. */
    public void filter(String query) {
        this.filter = query == null ? "" : query;
        refresh();
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
        return TamboUIAdapter.list(
            visible.stream().map(label).toList())
            .selected(Math.min(selected, Math.max(0, visible.size() - 1)))
            .highlightColor(dev.tamboui.style.Color.CYAN)
            .rounded()
            .title("Select (↑/↓ enter, Esc cancel)")
            .fill();
    }

    private void refresh() {
        visible.clear();
        visible.addAll(FuzzyMatcher.rank(filter, allItems, label));
        selected = 0;
    }
}
