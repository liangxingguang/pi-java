package com.pijava.tui.component;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.elements.Row;

/**
 * Bottom status bar: session name + tokens + model, driven by the
 * agent-core {@link SessionSnapshot} (Phase 3 design §4.4).
 */
public final class StatusBar {

    /** Render the status bar for a snapshot. */
    public Row render(SessionSnapshot snapshot) {
        return TamboUIAdapter.row(
            TamboUIAdapter.text(" " + snapshot.name()).dim(),
            TamboUIAdapter.spacerFill(),
            running(snapshot),
            TamboUIAdapter.text("\u26A1 " + snapshot.totalTokens() + " tokens").dim(),
            TamboUIAdapter.text(" | ").dim(),
            TamboUIAdapter.text(snapshot.model()).dim())
            .length(1).addClass("StatusBar");
    }

    /** A live "running" indicator while the agent is working (empty when idle). */
    private static dev.tamboui.toolkit.elements.TextElement running(SessionSnapshot snapshot) {
        if ("running".equals(snapshot.phase())) {
            return TamboUIAdapter.text("\u25CF running ").cyan();
        }
        return TamboUIAdapter.text("").dim();
    }
}
