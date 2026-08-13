package com.pijava.tui.component;

import java.util.List;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * Unified diff renderer with line coloring (Phase 3 design §4.3).
 */
public final class DiffView {

    /** Render a unified diff text into a bordered widget. */
    public Element render(String diffText) {
        var lines = diffText.lines()
            .map(line -> switch (line) {
                case String l when l.startsWith("+") ->
                    TamboUIAdapter.text(l).green();
                case String l when l.startsWith("-") ->
                    TamboUIAdapter.text(l).red();
                case String l when l.startsWith("@@") ->
                    TamboUIAdapter.text(l).cyan();
                default -> TamboUIAdapter.text(line);
            })
            .toList();
        return TamboUIAdapter.panel(
            TamboUIAdapter.column(List.copyOf(lines))).rounded();
    }
}
