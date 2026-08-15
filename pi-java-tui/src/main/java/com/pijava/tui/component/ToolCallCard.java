package com.pijava.tui.component;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;

/**
 * Tool call line (Phase 3 design §4.3): rendered as plain text like the rest
 * of the conversation — no border, no background (Codex-CLI style). The
 * arguments are pre-wrapped to the chat content width so the row height
 * measured by the {@code ListElement} always matches the rendered height.
 */
public record ToolCallCard(
    String name,
    String arguments,
    String status
) {
    /** Render as a plain two-line block: name/status, then arguments. */
    public Element render(int contentWidth) {
        return TamboUIAdapter.column(
            TamboUIAdapter.row(
                TamboUIAdapter.text(name).bold().cyan(),
                TamboUIAdapter.spacerFill(),
                TamboUIAdapter.text(status).dim()),
            TamboUIAdapter.text(
                MessageBubble.wrap(truncate(arguments, 200), contentWidth)).dim());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
