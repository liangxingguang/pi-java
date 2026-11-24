package com.pijava.tui.component;

import com.pijava.tui.util.TextLayout;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

/**
 * L7 W6（C4）：{@link SingleLineInput} 的宽度感知渲染件。
 *
 * <p>渲染 {@code prompt + 可见文本 + 补白}；光标单元格反白（pi 的假光标
 * {@code \x1b[7m} 对位）；空值且有待占位文案时占位首字符反白、其余 dim。
 * 左键按下按可见列定位光标。{@link #renderRow} 供搜索条/设置列表复用。</p>
 */
public final class SingleLineInputElement extends StyledElement<SingleLineInputElement> {

    private final SingleLineInput input;
    private boolean focused = true;
    private Rect lastArea;

    /** Creates an element over the given input state. */
    public SingleLineInputElement(SingleLineInput input) {
        this.input = input;
    }

    /** Focus flag (false ⇒ no reverse-video cursor). */
    public SingleLineInputElement focused(boolean value) {
        this.focused = value;
        return this;
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight,
                              RenderContext context) {
        return Size.of(Math.min(availableWidth,
            TextLayout.displayWidth(input.prompt()) + input.totalWidth() + 1), 1);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        lastArea = area;
        renderRow(frame.buffer(), area.left(), area.top(), area.width(),
            input, focused, context.currentStyle());
    }

    /**
     * 把单行输入画到缓冲区（不超出 {@code width}）。
     *
     * @param buffer  目标缓冲
     * @param x       起始列
     * @param y       行
     * @param width   可用列宽
     * @param input   输入状态
     * @param focused 是否显示反白光标
     * @param base    基础样式
     */
    public static void renderRow(Buffer buffer, int x, int y, int width,
                                 SingleLineInput input, boolean focused, Style base) {
        int promptWidth = TextLayout.displayWidth(input.prompt());
        if (width <= 0) {
            return;
        }
        if (promptWidth >= width) {
            buffer.setString(x, y, TextLayout.truncateToWidth(input.prompt(), width), base);
            return;
        }
        buffer.setString(x, y, input.prompt(), base);
        int startX = x + promptWidth;
        int available = width - promptWidth;
        if (input.value().isEmpty() && !input.placeholder().isEmpty()) {
            renderPlaceholder(buffer, startX, y, available, base, focused, input.placeholder());
            return;
        }
        var view = input.view(available);
        int cursorIndex = focused ? view.cursorIndex() : -1;
        int col = 0;
        for (int i = 0; i < view.text().length(); ) {
            int cp = view.text().codePointAt(i);
            int w = TextLayout.isWide(cp) ? 2 : 1;
            if (col + w > available) {
                break;
            }
            var style = i == cursorIndex ? base.reversed() : base;
            buffer.setString(startX + col, y, new String(Character.toChars(cp)), style);
            col += w;
            i += Character.charCount(cp);
        }
        if (cursorIndex >= 0 && cursorIndex >= view.text().length() && col < available) {
            buffer.setString(startX + col, y, " ", base.reversed());
        }
    }

    /** 占位文案：首字符反白作光标，其余 dim。 */
    private static void renderPlaceholder(Buffer buffer, int x, int y, int available,
                                          Style base, boolean focused, String placeholder) {
        String text = TextLayout.truncateToWidth(placeholder, available);
        String plain = TextLayout.plainText(text);
        var dim = base.dim();
        int col = 0;
        boolean first = true;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            int w = TextLayout.isWide(cp) ? 2 : 1;
            if (col + w > available) {
                break;
            }
            var style = first && focused ? dim.reversed() : dim;
            buffer.setString(x + col, y, new String(Character.toChars(cp)), style);
            first = false;
            col += w;
            i += Character.charCount(cp);
        }
        if (first && focused && available > 0) {
            buffer.setString(x, y, " ", dim.reversed());
        }
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        if (lastArea == null || event.kind() != MouseEventKind.PRESS) {
            return EventResult.UNHANDLED;
        }
        int promptWidth = TextLayout.displayWidth(input.prompt());
        input.setCursorByColumn(event.x() - lastArea.left() - promptWidth);
        return EventResult.HANDLED;
    }
}
