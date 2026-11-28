package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.LineHighlighter;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;

/**
 * L7 W6/W8（S2a/S2b）：视口交互态——搜索高亮、拖拽选区、复制回调与本帧行快照。
 *
 * <p>从 {@link ChatViewportElement} 拆出以守 500 行 Checkstyle 限；渲染仍由
 * 视口驱动（行解析在 {@code toLine}，本类只给列区间）。S2 补齐：双击/三击
 * 粒度经 {@link TranscriptSelection}，拖选边缘自动滚动状态（pi
 * {@code tui-alt-screen.ts:1282-1336}，50ms/行）也收在本类。</p>
 */
final class ViewportInteraction {

    /** 自动滚动节奏（pi 每 50ms 滚一行）。 */
    private static final long AUTO_SCROLL_INTERVAL_MS = 50;

    private List<ChatViewportElement.Highlight> highlights = List.of();
    private final TranscriptSelection selection = new TranscriptSelection();
    private java.util.function.Consumer<String> sink;
    private List<RenderRow> rows = List.of();
    private int pointerX;
    private int pointerY;
    private int autoScrollDirection;
    private long lastAutoScrollMs = Long.MIN_VALUE;

    void setRows(List<RenderRow> value) {
        rows = value == null ? List.of() : value;
    }

    void setSink(java.util.function.Consumer<String> value) {
        sink = value;
    }

    List<RenderRow> rows() {
        return rows;
    }

    void setHighlights(List<ChatViewportElement.Highlight> value) {
        highlights = value == null ? List.of() : List.copyOf(value);
    }

    List<ChatViewportElement.Highlight> highlights() {
        return highlights;
    }

    boolean selectionActive() {
        return selection.active();
    }

    /** 本次按下后是否发生过拖动（区分点击与拖选，pi {@code selectionDragged}）。 */
    boolean selectionDragged() {
        return selection.dragged();
    }

    /**
     * 内容区按下：已按下 ⇒ 继续拖动（Windows X10 重复 PRESS），否则按
     * 双击/三击计数建立字符/词/行选区。
     */
    void press(int x, int y, Rect area, int offset, int contentWidth,
               long nowMs) {
        var cell = cell(x, y, area, offset, contentWidth);
        if (selection.active()) {
            selection.drag(cell, rows);
        } else {
            selection.press(cell, nowMs, rows);
        }
    }

    void drag(int x, int y, Rect area, int offset, int contentWidth) {
        if (selection.active()) {
            selection.drag(cell(x, y, area, offset, contentWidth), rows);
        }
    }

    /** 记录拖拽指针并按位置更新边缘自动滚动方向（pi {@code :1282-1308}）。 */
    void setPointer(int x, int y, Rect area) {
        pointerX = x;
        pointerY = y;
        int direction = area == null ? 0
            : y <= area.y() ? -1 : y >= area.bottom() - 1 ? 1 : 0;
        if (direction != autoScrollDirection) {
            autoScrollDirection = direction;
            lastAutoScrollMs = Long.MIN_VALUE;
        }
    }

    void stopAutoScroll() {
        autoScrollDirection = 0;
        lastAutoScrollMs = Long.MIN_VALUE;
    }

    /**
     * 每个 tick 调用：指针在顶/底边缘且距上次 ≥50ms ⇒ 滚动一行并扩展选区。
     *
     * @return 选区/滚动发生变化 ⇒ true
     */
    boolean tickAutoScroll(long nowMs, Rect area, int offset, int contentWidth,
                           int totalRows, int visibleRows,
                           ChatViewportElement.ScrollState scroll) {
        if (!selection.active() || autoScrollDirection == 0 || area == null) {
            return false;
        }
        if (lastAutoScrollMs == Long.MIN_VALUE) {
            lastAutoScrollMs = nowMs;
            return false;
        }
        if (nowMs - lastAutoScrollMs < AUTO_SCROLL_INTERVAL_MS) {
            return false;
        }
        lastAutoScrollMs += Math.max(1, (nowMs - lastAutoScrollMs) / AUTO_SCROLL_INTERVAL_MS)
            * AUTO_SCROLL_INTERVAL_MS;
        int before = scroll.offset();
        scroll.scrollByRows(autoScrollDirection, totalRows, visibleRows);
        if (scroll.offset() == before) {
            stopAutoScroll();
            return false;
        }
        selection.drag(cell(pointerX, pointerY, area, scroll.offset(), contentWidth), rows);
        return true;
    }

    private java.util.function.BooleanSupplier copyOnSelect = () -> true;

    /** E2-54：{@code fullscreenCopyOnSelect} 现读源（缺省 true）。 */
    void setCopyOnSelectSource(java.util.function.BooleanSupplier source) {
        copyOnSelect = source == null ? () -> true : source;
    }

    /** 释放：有非空选区 ⇒ 结束选区；copy-on-select 开启时复制。 */
    boolean finishAndCopy() {
        stopAutoScroll();
        return finish(copyOnSelect.getAsBoolean());
    }

    /** 释放：有非空选区 ⇒ 结束选区；{@code copyOnSelect} 为真时复制。 */
    boolean finish(boolean copyOnSelect) {
        if (selection.active() && selection.finish()) {
            if (copyOnSelect) {
                copy();
            }
            return true;
        }
        return false;
    }

    boolean hasActiveSelection() {
        return selection.hasText();
    }

    boolean copyActiveSelection() {
        if (!selection.hasText()) {
            return false;
        }
        copy();
        return true;
    }

    void clearSelection() {
        selection.clear();
        stopAutoScroll();
    }

    /** 本行高亮区间：选区先、搜索后（当前匹配最高优先）。 */
    List<LineHighlighter.Range> rangesForRow(int row) {
        var ranges = new ArrayList<LineHighlighter.Range>();
        for (var hl : selection.highlights()) {
            if (hl.row() == row) {
                ranges.add(new LineHighlighter.Range(hl.startCol(), hl.endCol(),
                    Style.EMPTY.reversed()));
            }
        }
        for (var hl : highlights) {
            if (hl.row() == row) {
                var style = hl.current()
                    ? Style.EMPTY.reversed().bold() : Style.EMPTY.underlined();
                ranges.add(new LineHighlighter.Range(hl.startCol(), hl.endCol(), style));
            }
        }
        return ranges;
    }

    private void copy() {
        if (sink != null) {
            sink.accept(selection.text(rows));
        }
    }

    private static TranscriptSelection.Cell cell(int x, int y, Rect area,
                                                 int offset, int contentWidth) {
        int screenY = Math.max(area.y(), Math.min(y, area.bottom() - 1));
        int row = screenY - area.y() + offset;
        int col = Math.max(0, Math.min(x - area.x(), Math.max(0, contentWidth - 1)));
        return new TranscriptSelection.Cell(row, col);
    }
}
