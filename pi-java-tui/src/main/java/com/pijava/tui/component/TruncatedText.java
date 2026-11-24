package com.pijava.tui.component;

import com.pijava.tui.util.TextColumns;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;

/**
 * L7 W6（C13，pi {@code components/truncated-text.ts} 对位）：单行截断文本件。
 *
 * <p>只取首个换行前的内容；按 {@code width - 2*paddingX} 截断（超宽带 {@code …}）；
 * 左右 padding；补空格到恰好 {@code width}；上下各 {@code paddingY} 行空白。
 * 文本按可见文本渲染（markup 去标签），样式取父级当前样式。</p>
 */
public final class TruncatedText extends StyledElement<TruncatedText> {

    private final String text;
    private final int paddingX;
    private final int paddingY;

    /** Creates a truncated text element. */
    public TruncatedText(String text, int paddingX, int paddingY) {
        this.text = text == null ? "" : text;
        this.paddingX = Math.max(0, paddingX);
        this.paddingY = Math.max(0, paddingY);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight,
                              RenderContext context) {
        return Size.of(availableWidth, Math.min(availableHeight, 2 * paddingY + 1));
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        if (area.width() <= 0 || area.height() <= 0) {
            return;
        }
        var buffer = frame.buffer();
        var style = context.currentStyle();
        int blank = area.top();
        for (int i = 0; i < paddingY && blank + i < area.bottom(); i++) {
            buffer.setString(area.left(), blank + i, " ".repeat(area.width()), style);
        }
        int y = area.top() + paddingY;
        if (y >= area.bottom()) {
            return;
        }
        int inner = Math.max(0, area.width() - 2 * paddingX);
        String truncated = TextColumns.plainText(
            TextColumns.truncateToWidth(firstLine(), inner));
        int used = TextColumns.width(truncated);
        int right = Math.max(0, area.width() - paddingX - used);
        String line = " ".repeat(Math.min(paddingX, area.width()))
            + truncated + " ".repeat(right);
        buffer.setString(area.left(), y, line, style);
        for (int i = y + 1; i < area.bottom(); i++) {
            buffer.setString(area.left(), i, " ".repeat(area.width()), style);
        }
    }

    private String firstLine() {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
