package com.pijava.tui.component;

import dev.tamboui.style.Color;

/**
 * Discriminates the metadata/system bubble kinds so each gets its own icon
 * and color instead of a uniform dim line (P6-25, Phase 3 design §4.1).
 *
 * <p>{@link MetaKind#GENERIC} marks plain system messages (the startup card,
 * slash-command results, unknown message roles) and renders as the legacy dim
 * line with no icon. The other six values correspond to the agent-core
 * {@code Entry} metadata subtypes and carry a distinct glyph + color from the
 * pi dark theme palette.</p>
 */
public enum MetaKind {

    /** Model was switched ({@code Entry.ModelChange}). */
    MODEL_CHANGE("⚙", Color.hex("#7dcfff")),
    /** Thinking level was changed ({@code Entry.ThinkingLevelChange}). */
    THINKING_LEVEL("🧠", Color.hex("#bb9af7")),
    /** The active tool set was changed ({@code Entry.ActiveToolsChange}). */
    ACTIVE_TOOLS("🔧", Color.hex("#7aa2f7")),
    /** Context was compacted ({@code Entry.Compaction}). */
    COMPACTION("🗜", Color.hex("#e0af68")),
    /** A branch summary was generated ({@code Entry.BranchSummary}). */
    BRANCH("⎇", Color.hex("#9ece6a")),
    /** A custom extension event ({@code Entry.Custom}). */
    CUSTOM("◆", Color.hex("#565f89")),
    /** Plain system message: no icon, legacy dim style. */
    GENERIC(null, null);

    private final String icon;
    private final Color color;

    MetaKind(String icon, Color color) {
        this.icon = icon;
        this.color = color;
    }

    /** The glyph prefixed before the message, or {@code null} for GENERIC. */
    public String icon() {
        return icon;
    }

    /** The foreground color, or {@code null} for GENERIC (rendered dim). */
    public Color color() {
        return color;
    }
}
