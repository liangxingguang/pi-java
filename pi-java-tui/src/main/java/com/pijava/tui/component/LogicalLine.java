package com.pijava.tui.component;

import dev.tamboui.style.Style;

/**
 * A logical line of chat content (Phase 3 alignment design §5.1): terminal
 * width agnostic, mirroring Codex TUI2's HistoryCell (PR #8761). Wrapping is
 * a render-time derivation — resizing or scrolling never mutates this model,
 * so content can never be corrupted by a stale width.
 *
 * @param markup           raw text (may contain TamboUI markup tags)
 * @param initialIndent    indent of the first wrapped row (cells)
 * @param subsequentIndent indent of continuation rows (cells)
 * @param preformatted     never wrap; hard-truncate at the content width
 * @param style            whole-line style (EMPTY = default)
 */
public record LogicalLine(
    String markup,
    int initialIndent,
    int subsequentIndent,
    boolean preformatted,
    Style style
) {}
