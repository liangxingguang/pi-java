package com.pijava.tui.component;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * Tool call card: name + arguments + status (Phase 3 design §4.3).
 */
public record ToolCallCard(
    String name,
    String arguments,
    String status
) {
    /** Render the card as a bordered widget. */
    public Element render() {
        return TamboUIAdapter.panel(
            TamboUIAdapter.row(
                TamboUIAdapter.text("\uD83D\uDD27 " + name).bold(),
                TamboUIAdapter.spacerFill(),
                TamboUIAdapter.text(status).dim()),
            TamboUIAdapter.text(truncate(arguments, 200)).dim())
            .yellow().rounded().addClass("ToolCallCard");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
