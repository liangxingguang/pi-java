package com.pijava.tui.component;

import java.util.function.Supplier;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;

/**
 * L7 W6（C15，pi {@code components/dynamic-border.ts} 对位）：单行 {@code ─} 分隔横线。
 *
 * <p>颜色由 {@link Supplier} 现读（每帧取值 ⇒ thinking 等级/主题切换下一帧生效）。</p>
 */
public final class DynamicBorder extends StyledElement<DynamicBorder> {

    private final Supplier<Style> color;

    /** Creates a border line with a live style source (null ⇒ 无色). */
    public DynamicBorder(Supplier<Style> color) {
        this.color = color;
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight,
                              RenderContext context) {
        return Size.of(availableWidth, 1);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        if (area.width() <= 0 || area.height() <= 0) {
            return;
        }
        Style style = color == null ? Style.EMPTY : color.get();
        if (style == null) {
            style = Style.EMPTY;
        }
        frame.buffer().setString(area.left(), area.top(),
            "─".repeat(Math.max(1, area.width())), style);
    }
}
