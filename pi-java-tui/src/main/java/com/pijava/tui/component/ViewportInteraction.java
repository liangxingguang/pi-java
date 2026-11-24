package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.LineHighlighter;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;

/**
 * L7 W6（S2a/S2b）：视口交互态——搜索高亮、拖拽选区、复制回调与本帧行快照。
 *
 * <p>从 {@link ChatViewportElement} 拆出以守 500 行 Checkstyle 限；渲染仍由
 * 视口驱动（行解析在 {@code toLine}，本类只给列区间）。</p>
 */
final class ViewportInteraction {

    private List<ChatViewportElement.Highlight> highlights = List.of();
    private final TranscriptSelection selection = new TranscriptSelection();
    private java.util.function.Consumer<String> sink;
    private List<RenderRow> rows = List.of();

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

    /** 内容区按下：新锚点；已按下 ⇒ 继续拖动（Windows X10 重复 PRESS）。 */
    void press(int x, int y, Rect area, int offset, int contentWidth) {
        var cell = cell(x, y, area, offset, contentWidth);
        if (selection.active()) {
            selection.drag(cell);
        } else {
            selection.press(cell);
        }
    }

    void drag(int x, int y, Rect area, int offset, int contentWidth) {
        if (selection.active()) {
            selection.drag(cell(x, y, area, offset, contentWidth));
        }
    }

    /** 释放：有非空选区 ⇒ 复制并返回 true。 */
    boolean finishAndCopy() {
        if (selection.active() && selection.finish()) {
            copy();
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
        int row = y - area.y() + offset;
        int col = Math.max(0, Math.min(x - area.x(), Math.max(0, contentWidth - 1)));
        return new TranscriptSelection.Cell(row, col);
    }
}
