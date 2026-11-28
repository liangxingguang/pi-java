package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TextLayout;
import com.pijava.tui.util.WordSegments;

/**
 * L7 W6/W8（S2b，pi {@code tui-alt-screen.ts} 选区对位）：拖拽选区状态与文本提取。
 *
 * <p>点以「内容行号（含滚动偏移）＋显示列」记录；按下→拖动→释放；
 * 反向拖拽自动排序；文本逐行按列切可见文本、行尾去尾随空格、行间 {@code \n}。
 * S2 补齐：双击选词、三击行选（{@link Granularity}＋{@code initialRange}）、
 * 点击计数窗口与拖动按词/行扩展（pi {@code :1236-1280}）。end 列排他。</p>
 */
public final class TranscriptSelection {

    /** 选区端点（行号含滚动偏移；列＝显示列）。 */
    public record Cell(int row, int col) {}

    /** 选取粒度（pi {@code selectionGranularity}）。 */
    public enum Granularity { CHARACTER, WORD, LINE }

    private record CellRange(Cell start, Cell end) {}

    private record LastClick(long timestamp, int count, int row, int wordStart, int wordEnd) {}

    private static final long DOUBLE_CLICK_INTERVAL_MS = WordSegments.doubleClickIntervalMs();

    private Cell anchor;
    private Cell focus;
    private boolean pressed;
    private boolean dragged;
    private Granularity granularity = Granularity.CHARACTER;
    private CellRange initialRange;
    private LastClick lastClick;

    /** 按下：字符粒度新锚点，清旧选区（保持既有 API 语义）。 */
    public void press(Cell cell) {
        anchor = cell;
        focus = cell;
        pressed = true;
        dragged = false;
        granularity = Granularity.CHARACTER;
        initialRange = null;
        lastClick = null;
    }

    /**
     * 按下：按双击/三击计数选择字符、词或整行（pi {@code :1338-1416}）。
     *
     * @param cell  点击点
     * @param nowMs 事件时间（毫秒）
     * @param rows  当前渲染行（取词/行文本）
     */
    public void press(Cell cell, long nowMs, List<RenderRow> rows) {
        CellRange word = wordRange(cell, rows);
        LastClick previous = lastClick;
        int count = word != null && previous != null
            && nowMs - previous.timestamp() <= DOUBLE_CLICK_INTERVAL_MS
            && previous.row() == cell.row()
            && previous.wordStart() == word.start().col()
            && previous.wordEnd() == word.end().col()
                ? (previous.count() % 3) + 1
                : 1;
        lastClick = word == null ? null
            : new LastClick(nowMs, count, cell.row(), word.start().col(), word.end().col());
        CellRange range = switch (count) {
            case 2 -> word;
            case 3 -> lineRange(cell, rows);
            default -> null;
        };
        granularity = range == null ? Granularity.CHARACTER
            : count == 2 ? Granularity.WORD : Granularity.LINE;
        initialRange = range;
        anchor = range == null ? cell : range.start();
        focus = range == null ? cell : range.end();
        pressed = true;
        dragged = false;
    }

    /** 拖动：更新焦点（未按下时忽略；字符粒度）。 */
    public void drag(Cell cell) {
        if (pressed) {
            focus = cell;
            dragged = true;
            lastClick = null;
        }
    }

    /**
     * 拖动：字符粒度更新焦点；词/行粒度按当前点重算范围并扩展（pi {@code :1236-1254}）。
     *
     * @param cell 当前拖动点
     * @param rows 当前渲染行
     */
    public void drag(Cell cell, List<RenderRow> rows) {
        if (!pressed) {
            return;
        }
        dragged = true;
        lastClick = null;
        if (granularity == Granularity.CHARACTER || initialRange == null) {
            focus = cell;
            return;
        }
        CellRange range = granularity == Granularity.WORD
            ? wordRange(cell, rows) : lineRange(cell, rows);
        if (range == null) {
            return;
        }
        boolean beforeInitial = range.start().row() < initialRange.start().row()
            || range.start().row() == initialRange.start().row()
                && range.start().col() < initialRange.start().col();
        if (beforeInitial) {
            anchor = initialRange.end();
            focus = range.start();
        } else {
            anchor = initialRange.start();
            focus = range.end();
        }
    }

    /**
     * 释放：返回是否存在非空选区（不改变选区内容，供复制与继续高亮）。
     *
     * @return 非空选区 ⇒ true
     */
    public boolean finish() {
        boolean selected = hasText();
        pressed = false;
        return selected;
    }

    /** 是否有进行中的拖拽（按下未释放）。 */
    public boolean active() {
        return pressed;
    }

    /** 本次按下后是否发生过拖动（pi {@code selectionDragged}：区分点击与拖选）。 */
    public boolean dragged() {
        return dragged;
    }

    /** 是否有非空选区。 */
    public boolean hasText() {
        return anchor != null && focus != null
            && !(anchor.row() == focus.row() && anchor.col() == focus.col());
    }

    /** 清空选区（点击计数保留，与 pi {@code clearTextSelection} 一致）。 */
    public void clear() {
        anchor = null;
        focus = null;
        pressed = false;
        dragged = false;
        granularity = Granularity.CHARACTER;
        initialRange = null;
    }

    /**
     * 提取选区文本（行尾去尾随空格，行间换行）。
     *
     * @param rows 当前渲染行
     * @return 选区文本（无选区 ⇒ 空串）
     */
    public String text(List<RenderRow> rows) {
        if (!hasText() || rows == null || rows.isEmpty()) {
            return "";
        }
        var ordered = ordered();
        var start = ordered[0];
        var end = ordered[1];
        var out = new StringBuilder();
        for (int row = start.row(); row <= end.row() && row < rows.size(); row++) {
            String line = TextLayout.plainText(rows.get(row).text());
            int width = TextLayout.displayWidth(line);
            int from = row == start.row() ? start.col() : 0;
            int to = row == end.row() ? end.col() : width;
            String piece = TextLayout.sliceByColumns(line, from, to);
            if (row > start.row()) {
                out.append('\n');
            }
            out.append(stripTrailing(piece));
        }
        return out.toString();
    }

    /** 选区高亮（反白）区间，按行拆分。 */
    public List<ChatViewportElement.Highlight> highlights() {
        var out = new ArrayList<ChatViewportElement.Highlight>();
        if (!hasText()) {
            return out;
        }
        var ordered = ordered();
        var start = ordered[0];
        var end = ordered[1];
        for (int row = start.row(); row <= end.row(); row++) {
            int from = row == start.row() ? start.col() : 0;
            int to = row == end.row() ? end.col() : Integer.MAX_VALUE / 2;
            out.add(new ChatViewportElement.Highlight(row, from, to, false));
        }
        return out;
    }

    private CellRange wordRange(Cell cell, List<RenderRow> rows) {
        if (rows == null || cell.row() < 0 || cell.row() >= rows.size()) {
            return null;
        }
        String line = TextLayout.plainText(rows.get(cell.row()).text());
        WordSegments.Range range = WordSegments.wordRangeAt(line, cell.col());
        if (range == null) {
            return null;
        }
        return new CellRange(new Cell(cell.row(), range.start()),
            new Cell(cell.row(), range.end()));
    }

    private CellRange lineRange(Cell cell, List<RenderRow> rows) {
        if (rows == null || cell.row() < 0 || cell.row() >= rows.size()) {
            return null;
        }
        String line = TextLayout.plainText(rows.get(cell.row()).text());
        return new CellRange(new Cell(cell.row(), 0),
            new Cell(cell.row(), TextLayout.displayWidth(line)));
    }

    private Cell[] ordered() {
        if (anchor.row() < focus.row()
            || anchor.row() == focus.row() && anchor.col() <= focus.col()) {
            return new Cell[] {anchor, focus};
        }
        return new Cell[] {focus, anchor};
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }
}
