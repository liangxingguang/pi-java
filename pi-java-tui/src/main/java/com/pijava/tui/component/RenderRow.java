package com.pijava.tui.component;

import dev.tamboui.style.Style;

/**
 * One physical row produced by wrapping a {@link LogicalLine} at a given
 * terminal width (Phase 3 alignment design §5.1).
 *
 * @param text  the row content (may contain TamboUI markup tags)
 * @param style the style to patch onto the rendered row
 * @param image inline image payload carried by the first row of an image block
 */
public record RenderRow(String text, Style style, ImagePlacement image) {

    /** 文本行便捷构造（image = null）。 */
    public RenderRow(String text, Style style) {
        this(text, style, null);
    }
}
