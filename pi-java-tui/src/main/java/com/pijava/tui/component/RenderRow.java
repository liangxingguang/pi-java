package com.pijava.tui.component;

import dev.tamboui.style.Style;

/**
 * One physical row produced by wrapping a {@link LogicalLine} at a given
 * terminal width (Phase 3 alignment design §5.1).
 *
 * @param text  the row content (may contain TamboUI markup tags)
 * @param style the style to patch onto the rendered row
 */
public record RenderRow(String text, Style style) {}
