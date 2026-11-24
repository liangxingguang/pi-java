package com.pijava.tui.util;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.component.RenderRow;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

/**
 * L7 W6（S2a/S2b）：按显示列给已渲染行打高亮。
 *
 * <p>先 {@link TextLayout#toLine} 解析出 span 序列（行样式作底、span 优先），
 * 再按列区间把 span 切成段，区间内 {@code style().patch(range.style())}。
 * 宽字符整体纳入；区间落在字符中间时收敛到字符边界。</p>
 */
public final class LineHighlighter {

    /** 列区间高亮（{@code [startCol,endCol)}，显示列）。 */
    public record Range(int startCol, int endCol, Style style) {}

    private LineHighlighter() {}

    /**
     * 应用高亮区间；无区间时原样返回解析结果。
     *
     * @param row    渲染行（markup）
     * @param ranges 高亮区间（可重叠，后写覆盖先写）
     * @return 打上高亮的单行
     */
    public static Line apply(RenderRow row, List<Range> ranges) {
        Line line = TextLayout.toLine(row);
        if (ranges == null || ranges.isEmpty()) {
            return line;
        }
        var out = new ArrayList<Span>();
        int col = 0;
        for (Span span : line.spans()) {
            var content = span.content();
            var buffer = new StringBuilder();
            var bufferStyle = span.style();
            int spanStartCol = col;
            for (int i = 0; i < content.length(); ) {
                int cp = content.codePointAt(i);
                int w = TextLayout.isWide(cp) ? 2 : 1;
                var style = spanStyleAt(span.style(), ranges, col);
                if (buffer.length() > 0 && !style.equals(bufferStyle)) {
                    out.add(Span.styled(buffer.toString(), bufferStyle));
                    buffer.setLength(0);
                }
                bufferStyle = style;
                buffer.appendCodePoint(cp);
                col += w;
                i += Character.charCount(cp);
            }
            if (buffer.length() > 0) {
                out.add(Span.styled(buffer.toString(), bufferStyle));
            }
            if (col == spanStartCol) {
                out.add(Span.styled("", span.style()));
            }
        }
        return Line.from(out);
    }

    private static Style spanStyleAt(Style base, List<Range> ranges, int col) {
        var style = base;
        for (var range : ranges) {
            if (col >= range.startCol() && col < range.endCol()) {
                style = style.patch(range.style());
            }
        }
        return style;
    }
}
