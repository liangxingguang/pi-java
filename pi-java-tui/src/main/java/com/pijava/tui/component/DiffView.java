package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Unified diff renderer (P6-26): colors a unified diff into width-agnostic
 * {@link LogicalLine}s, aligning with pi's {@code renderDiff}. Added lines are
 * green, removed lines red, hunk/file headers cyan, context lines dim. Lines
 * are preformatted (never wrapped) so the {@code -}/{@code +} column stays
 * aligned.
 */
public final class DiffView {

    /** Added-line color (pi dark theme green). */
    static final Color ADDED = Color.hex("#9ece6a");
    /** Removed-line color (pi dark theme red). */
    static final Color REMOVED = Color.hex("#f7768e");
    /** Hunk/file header color (pi dark theme cyan). */
    static final Color HEADER = Color.hex("#7dcfff");

    private DiffView() {}

    /**
     * Colors a unified diff into logical lines.
     *
     * @param diffText the diff text (lines prefixed with {@code -}/{@code +}/
     *        {@code ' '}, optionally {@code @@}/{@code ---}/{@code +++} headers)
     * @return logical lines, empty when {@code diffText} is null or blank
     */
    public static List<LogicalLine> lines(String diffText) {
        if (diffText == null || diffText.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<LogicalLine>();
        for (var raw : diffText.split("\r?\n", -1)) {
            out.add(new LogicalLine(
                TextLayout.escapeMarkup(raw), 0, 0, true, styleOf(raw)));
        }
        return out;
    }

    private static Style styleOf(String line) {
        if (line.isEmpty()) {
            return Style.EMPTY.dim();
        }
        char c = line.charAt(0);
        if (c == '@' || line.startsWith("---") || line.startsWith("+++")) {
            return Style.EMPTY.fg(HEADER);
        }
        if (c == '+') {
            return Style.EMPTY.fg(ADDED);
        }
        if (c == '-') {
            return Style.EMPTY.fg(REMOVED);
        }
        return Style.EMPTY.dim();
    }
}
