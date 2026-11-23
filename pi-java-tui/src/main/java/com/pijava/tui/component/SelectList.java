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
 *
 * <p>docs/62（L7 W2，pi {@code tui/components/select-list.ts}）：PgUp/PgDn 整页翻，
 * 页大小＝{@link #maxVisible}（默认 5，pi {@code :46}），<b>clamp 不 wrap</b>
 * （pi {@code keybindings.ts:327-339}）；可见窗口以选中项居中（pi {@code :181-188}）。</p>
 */
public final class SelectList<T> {

    /** pi {@code select-list.ts:46} 的默认可见项数。 */
    public static final int DEFAULT_MAX_VISIBLE = 5;

    private final List<T> allItems;
    private final Function<T, String> label;
    private final List<T> visible = new ArrayList<>();
    private final int maxVisible;
    private int selected;
    private boolean cancelled;
    private boolean confirmed;
    private String filter = "";
    // L5 W9（pi 5bcf370a5）：搜索框内光标——Left/Right/Home/End 只移光标，不重过滤。
    private int cursor;

    /**
     * Creates a selectable list over the given items.
     *
     * @param items all items in their canonical order
     * @param label maps an item to its display label
     */
    public SelectList(List<T> items, Function<T, String> label) {
        this(items, label, DEFAULT_MAX_VISIBLE);
    }

    /**
     * Creates a selectable list with an explicit visible-item count
     * (page size for PgUp/PgDn, also the render window).
     *
     * @param items      all items in their canonical order
     * @param label      maps an item to its display label
     * @param maxVisible visible items (pi per-selector {@code maxVisible})
     */
    public SelectList(List<T> items, Function<T, String> label, int maxVisible) {
        this.allItems = List.copyOf(items);
        this.label = label;
        this.maxVisible = Math.max(1, maxVisible);
        refresh();
    }

    /** Handle selection keys; returns true when a key was consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isPageUp()) {
            page(-1);
            return true;
        }
        if (event.isPageDown()) {
            page(1);
            return true;
        }
        if (event.isUp()) {
            selected = Math.max(0, selected - 1);
            return true;
        }
        if (event.isDown()) {
            selected = Math.min(visible.size() - 1, selected + 1);
            return true;
        }
        // L5 W9：搜索框光标键只移动光标（不 refresh、不重置选中）。
        if (event.isLeft()) {
            cursor = Math.max(0, cursor - 1);
            return true;
        }
        if (event.isRight()) {
            cursor = Math.min(filter.length(), cursor + 1);
            return true;
        }
        if (event.code() == KeyCode.HOME) {
            cursor = 0;
            return true;
        }
        if (event.code() == KeyCode.END) {
            cursor = filter.length();
            return true;
        }
        if (event.isConfirm()) {
            confirmed = true;
            return true; // caller reads selected()
        }
        if (event.isKey(KeyCode.BACKSPACE)) {
            if (cursor == 0) {
                return false;
            }
            filter = filter.substring(0, cursor - 1) + filter.substring(cursor);
            cursor--;
            refresh(true);
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
            filter = filter.substring(0, cursor) + event.character() + filter.substring(cursor);
            cursor++;
            refresh(true);
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

    /** L5 W9：搜索框光标位置（{@code 0..filter().length()}；输入/退格在此处生效）。 */
    public int cursorPosition() {
        return cursor;
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

    /** 批 34（S8）：过滤后的可见项（自绘列表读点）。 */
    public List<T> visibleItems() {
        return List.copyOf(visible);
    }

    /** 批 34（S8）：当前选中下标（钳过界；空表为 0）。 */
    public int selectedIndex() {
        return Math.min(selected, Math.max(0, visible.size() - 1));
    }

    /**
     * 整页移动（pi {@code getSelectPageIndex}）：clamp 不 wrap；空表或零位移
     * 返回 {@code false}（pi {@code select-list.ts:161} 的"无位移不发事件"）。
     *
     * @param direction {@code -1}＝PgUp，{@code +1}＝PgDn
     * @return 是否发生了位移
     */
    public boolean page(int direction) {
        if (visible.isEmpty()) {
            return false;
        }
        int target = Math.max(0,
            Math.min(visible.size() - 1, selected + direction * maxVisible));
        if (target == selected) {
            return false;
        }
        selected = target;
        return true;
    }

    /**
     * 居中窗口（pi {@code :181-188}）：选中项永远在窗口内，窗口大小＝{@link #maxVisible}。
     *
     * @return 渲染窗口内的可见项（项数 ≤ maxVisible）
     */
    public List<T> visibleRange() {
        if (visible.size() <= maxVisible) {
            return List.copyOf(visible);
        }
        int start = Math.max(0, Math.min(selected - maxVisible / 2, visible.size() - maxVisible));
        return List.copyOf(visible.subList(start, start + maxVisible));
    }

    /** 窗口内选中项的下标（渲染高亮用；pi 每帧由 {@code getVisibleRange} 重算）。 */
    public int windowSelectedIndex() {
        if (visible.size() <= maxVisible) {
            return selectedIndex();
        }
        int start = Math.max(0, Math.min(selected - maxVisible / 2, visible.size() - maxVisible));
        return Math.max(0, Math.min(maxVisible - 1, selected - start));
    }

    /** Reset selection state (e.g. when reopening the selector). */
    public void reset() {
        selected = 0;
        cancelled = false;
        confirmed = false;
    }

    /**
     * Move the selection to the first visible item matching {@code pred}
     * (docs/18 §3.10：重开选择器时恢复上次选中，pi {@code initialSelectedId} 对位)。
     *
     * @return false when no item matches (selection unchanged)
     */
    public boolean selectItem(java.util.function.Predicate<T> pred) {
        for (int i = 0; i < visible.size(); i++) {
            if (pred.test(visible.get(i))) {
                selected = i;
                return true;
            }
        }
        return false;
    }

    /** Render the list with the current selection highlighted. */
    public Element render() {
        String title = filter.isEmpty()
            ? "Select (type to filter, ↑/↓ enter, Esc cancel)"
            : "Filter: \"" + filter + "\" (Esc clear, enter confirm)";
        return TamboUIAdapter.list(
            visibleRange().stream().map(label).toList())
            .selected(windowSelectedIndex())
            .highlightColor(dev.tamboui.style.Color.CYAN)
            .rounded()
            .title(title)
            .fill();
    }

    private void refresh() {
        refresh(false);
    }

    /**
     * 重算可见集；{@code keepCursor} ⇒ 光标已由调用方维护（输入/退格），
     * 否则钉到查询末尾（程序化 filter/清空）。
     */
    private void refresh(boolean keepCursor) {
        visible.clear();
        visible.addAll(FuzzyMatcher.rank(filter, allItems, label));
        selected = 0;
        if (!keepCursor) {
            cursor = filter.length();
        }
    }
}
