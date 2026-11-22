package com.pijava.tui.app;

import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.SessionCwd;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;

/**
 * Startup selector for a resumed session whose cwd no longer exists
 * (pi {@code showStartupSelector}, L5 W3).
 *
 * <p>Runs a bare full-screen list before the main UI exists, like
 * {@link TrustPromptRunner}. Esc/absence of a terminal cancels (exit 0).</p>
 */
public final class SessionCwdPromptRunner {

    private SessionCwdPromptRunner() {}

    /** Ask the user; a null result means cancel. */
    public static SessionCwd.Choice prompt(SessionCwd.Issue issue) {
        if (System.console() == null && !Boolean.getBoolean("pi-java.dev.forceTui")) {
            return SessionCwd.Choice.CANCEL;
        }
        var settings = SettingsManager.load(null);
        dev.tamboui.toolkit.app.ToolkitRunner runner;
        try {
            runner = TamboUIAdapter.createRunner(
                settings.uiPrefs().getShowHardwareCursor());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the session-cwd prompt TUI", e);
        }
        com.pijava.coding.agent.theme.Themes.initTheme(
            com.pijava.coding.agent.theme.ThemeSettings.resolveThemeSetting(
                null, com.pijava.coding.agent.theme.Themes.terminalTheme()), false);
        runner.styleEngine(com.pijava.tui.theme.ThemeApplicator.engineForCurrentTheme());

        var options = SessionCwd.options(issue);
        var labels = options.stream().map(SessionCwd.Option::label).toList();
        var selected = new AtomicInteger(0);
        var done = new boolean[] {false};
        var cancelled = new boolean[] {false};
        var cols = new AtomicInteger(80);
        runner.eventRouter().addGlobalHandler(event -> {
            if (event instanceof ResizeEvent resize) {
                cols.set(resize.width());
                return EventResult.HANDLED;
            }
            if (event instanceof KeyEvent keyEvent) {
                if (keyEvent.isUp()) {
                    selected.set(Math.max(0, selected.get() - 1));
                } else if (keyEvent.isDown()) {
                    selected.set(Math.min(labels.size() - 1, selected.get() + 1));
                } else if (keyEvent.isConfirm()) {
                    done[0] = true;
                } else if (keyEvent.isCancel()) {
                    cancelled[0] = true;
                    done[0] = true;
                }
                if (done[0]) {
                    runner.quit();
                }
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        });
        try {
            runner.run(() -> TamboUIAdapter.column(
                TamboUIAdapter.text(SessionCwd.formatPrompt(issue)),
                TamboUIAdapter.spacer(),
                TamboUIAdapter.list(labels)
                    .selected(selected.get())
                    .highlightColor(dev.tamboui.style.Color.CYAN)
                    .rounded()
                    .fill()));
        } catch (Exception e) {
            throw new IllegalStateException("Session-cwd prompt failed: " + e.getMessage(), e);
        }
        if (cancelled[0]) {
            return null;
        }
        return options.get(selected.get()).value();
    }
}
