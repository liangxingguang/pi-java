package com.pijava.tui.screen;

import java.util.List;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Settings page: grouped read-only view of effective settings
 * (Phase 3 design §8.1; interactive field editing lands Phase 6).
 */
public final class SettingsScreen implements ScreenOverlay {

    private boolean closed;
    private final AgentSession session;

    public SettingsScreen(AgentSession session) {
        this.session = session;
    }

    /** Esc closes the page. */
    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isCancel()) {
            closed = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean isDone() {
        return closed;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // Read-only in Phase 3.
    }

    /** Render the effective settings grouped by category. */
    private Element renderSettings(com.pijava.coding.agent.core.Settings settings) {
        var rows = List.of(
            "Provider: " + value(settings.defaultProvider),
            "Model: " + value(settings.defaultModel),
            "Thinking: " + value(settings.defaultThinkingLevel),
            "Theme: " + value(settings.theme),
            "Steering mode: " + value(settings.steeringMode),
            "Follow-up mode: " + value(settings.followUpMode),
            "Project trust: " + value(settings.defaultProjectTrust),
            "TUI mode: " + value(settings.tuiMode),
            "Quiet startup: " + value(settings.quietStartup));
        return TamboUIAdapter.panel("Settings (read-only in Phase 3, Esc to close)",
            TamboUIAdapter.column(rows.stream()
                .map(TamboUIAdapter::text)
                .toList()))
            .fill();
    }

    @Override
    public Element render() {
        return renderSettings(session.services().settings().effective());
    }

    private static String value(Object field) {
        return field == null ? "(unset)" : String.valueOf(field);
    }
}
