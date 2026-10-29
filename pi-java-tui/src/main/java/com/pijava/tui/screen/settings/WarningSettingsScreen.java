package com.pijava.tui.screen.settings;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.Settings;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/** The single-warning submenu from settings-selector.ts:147-188. */
public final class WarningSettingsScreen implements ScreenOverlay {

    private final AgentSession session;
    private boolean done;

    /** Bind the warning submenu to the current session. */
    public WarningSettingsScreen(AgentSession session) {
        this.session = session;
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isCancel() || event.isCtrlC()) {
            done = true;
            return true;
        }
        if (event.isConfirm()) {
            var ui = session.services().settings().uiPrefs();
            var current = ui.getWarnings().anthropicExtraUsage();
            ui.setWarnings(new Settings.Warnings(current != null && !current));
            session.services().settings().flush();
            return true;
        }
        return false;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // Changes are written on Enter, exactly like the other settings rows.
    }

    @Override
    public Element render() {
        var warnings = session.services().settings().uiPrefs().getWarnings();
        var enabled = warnings.anthropicExtraUsage() == null || warnings.anthropicExtraUsage();
        return TamboUIAdapter.panel("Warnings",
            TamboUIAdapter.column(java.util.List.of(
                TamboUIAdapter.text("Anthropic extra usage  " + enabled),
                TamboUIAdapter.text("Warn when Anthropic subscription auth may use paid extra usage").dim(),
                TamboUIAdapter.text("Enter to change · Esc to go back").dim()))).fill();
    }
}
