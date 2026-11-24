package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TextLayout;

/**
 * L7 W6（S2b/K22，pi {@code tui-alt-screen.ts} 选区对位）：拖拽选区状态与文本提取。
 *
 * <p>点以「内容行号（含滚动偏移）＋显示列」记录；按下→拖动→释放；
 * 反向拖拽自动排序；文本逐行按列切可见文本、行尾去尾随空格、行间 {@code \n}。
 * 双击选词/边缘自动滚动等长尾见设计稿 §5 N3。</p>
 */
public final class TranscriptSelection {

    /** 选区端点（行号含滚动偏移；列＝显示列）。 */
    public record Cell(int row, int col) {}

    private Cell anchor;
    private Cell focus;
    private boolean pressed;

    /** 按下：新锚点，清旧选区。 */
    public void press(Cell cell) {
        anchor = cell;
        focus = cell;
        pressed = true;
    }

    /** 拖动：更新焦点（未按下时忽略）。 */
    public void drag(Cell cell) {
        if (pressed) {
            focus = cell;
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

    /** 是否有非空选区。 */
    public boolean hasText() {
        return anchor != null && focus != null
            && !(anchor.row() == focus.row() && anchor.col() == focus.col());
    }

    /** 清空选区。 */
    public void clear() {
        anchor = null;
        focus = null;
        pressed = false;
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
