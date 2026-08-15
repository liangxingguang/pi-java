package com.pijava.tui.component;

import java.util.List;

import dev.tamboui.style.Style;

/**
 * Tool call lines (Phase 3 alignment design §5.3): plain text like the rest
 * of the conversation — no border, no background (Codex-CLI style). The name
 * and status share one logical row (markup carries the intra-row styles); the
 * arguments are a single preformatted row hard-truncated at the content width.
 */
public record ToolCallCard(
    String name,
    String arguments,
    String status
) {
    /**
     * Builds the two logical rows: name/status, then the preformatted
     * arguments (truncated to 200 chars, never wrapped).
     *
     * @return logical lines
     */
    public List<LogicalLine> lines() {
        return List.of(
            new LogicalLine(
                "[cyan]" + name + "[/]  [dim]" + status + "[/]",
                0, 0, false, Style.EMPTY),
            new LogicalLine(
                truncate(arguments, 200), 4, 4, true, Style.EMPTY.dim()));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
