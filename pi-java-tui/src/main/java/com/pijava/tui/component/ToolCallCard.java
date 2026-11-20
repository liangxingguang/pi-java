package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.Style;

/**
 * Tool call lines (Phase 3 alignment design §5.3): plain text like the rest
 * of the conversation — no border, no background (Codex-CLI style). The name
 * and status share one logical row (markup carries the intra-row styles); the
 * arguments are a single preformatted row hard-truncated at the content width.
 *
 * <p>L7 W1（pi {@code render-utils.ts} 的 {@code toolTimerText}）：结果行可带耗时行
 * {@code Took 4.2s}（{@link ToolDurations#timerText}），{@code null} ≙ 无计时。</p>
 */
public record ToolCallCard(
    String name,
    String arguments,
    String status,
    Long durationMillis
) {
    /** 无计时形（{@code null} ≙ pi 的 {@code durationMs} 键省略）。 */
    public ToolCallCard(String name, String arguments, String status) {
        this(name, arguments, status, null);
    }

    /**
     * Builds the logical rows: name/status, the preformatted arguments
     * (truncated to 200 chars, never wrapped), then the optional timing row.
     *
     * @return logical lines
     */
    public List<LogicalLine> lines() {
        var rows = new ArrayList<LogicalLine>(3);
        rows.add(new LogicalLine(
            "[cyan]" + name + "[/]  [dim]" + status + "[/]",
            0, 0, false, Style.EMPTY));
        rows.add(new LogicalLine(
            truncate(arguments, 200), 4, 4, true, Style.EMPTY.dim()));
        if (durationMillis != null) {
            rows.add(new LogicalLine(
                "[dim]" + ToolDurations.timerText(false, durationMillis, 0L) + "[/]",
                4, 4, false, Style.EMPTY.dim()));
        }
        return List.copyOf(rows);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
