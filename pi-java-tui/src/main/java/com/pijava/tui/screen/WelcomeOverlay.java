package com.pijava.tui.screen;

import java.util.List;

import com.pijava.agent.harness.AgentHarness;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.util.TextLayout;

/**
 * Codex-CLI-style startup card: a rounded box with the product prompt and
 * version, the current model, the working directory and a tip line. It is
 * rendered as plain text (one System message at the top of the transcript),
 * so both fullscreen and regular TUI modes show it identically.
 */
public final class WelcomeOverlay {

    private static final int INNER_WIDTH = 54;

    private final String text;

    /** Test convenience constructor (no live session details). */
    public WelcomeOverlay() {
        this(null);
    }

    public WelcomeOverlay(AgentSession session) {
        this.text = buildCard(session);
    }

    /** The full multi-line card text (top border to bottom border). */
    public String text() {
        return text;
    }

    private static String buildCard(AgentSession session) {
        AgentHarness harness = session != null ? session.harness() : null;
        String modelText = harness != null && harness.getModel() != null
            ? "model:     " + harness.getModel().modelName()
                + levelSuffix(harness.getThinkingLevel())
            : "model:     unknown";
        String directory = System.getProperty("user.dir", ".");
        return String.join("\n",
            borderTop(),
            inner(">_ pi-java (v" + Version.VERSION + ")"),
            inner(""),
            inner(modelText + "   /model to change"),
            inner("directory: " + directory),
            borderBottom(),
            "",
            "  Tip: Enter 发送 · Shift+Enter 换行 · /help 查看命令");
    }

    private static String levelSuffix(ModelThinkingLevel level) {
        return level instanceof ModelThinkingLevel.Enabled enabled
            ? " " + enabled.level().label()
            : "";
    }

    private static String borderTop() {
        return "╭" + "─".repeat(INNER_WIDTH) + "╮";
    }

    private static String borderBottom() {
        return "╰" + "─".repeat(INNER_WIDTH) + "╯";
    }

    private static String inner(String text) {
        String clipped = text.length() <= INNER_WIDTH - 2
            ? text : text.substring(0, INNER_WIDTH - 2);
        int padding = Math.max(0, INNER_WIDTH - 2 - TextLayout.displayWidth(clipped));
        return "│ " + clipped + " ".repeat(padding) + " │";
    }
}