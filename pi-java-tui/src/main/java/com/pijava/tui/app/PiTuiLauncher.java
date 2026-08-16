package com.pijava.tui.app;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.theme.PiTheme;
import com.pijava.tui.util.InlineTuiShell;
import com.pijava.tui.util.ScrollConfig;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TuiEventDispatcher;

/**
 * Interactive-mode entry point: assembles the session + mode, registers
 * observers, and drives the fullscreen or regular TUI loop. Split out of
 * {@link PiTuiApp} to keep that class focused on the render loop.
 */
public final class PiTuiLauncher {

    private PiTuiLauncher() {}

    /**
     * Interactive-mode entry: assemble session + mode, register observers,
     * and run the TUI loop (Phase 3 design §11.1).
     */
    public static int runInteractive(Args args) {
        if (System.console() == null) {
            System.err.println("error: interactive mode requires a real terminal "
                + "(run from Windows Terminal / cmd / PowerShell)");
            return 1;
        }
        var session = AgentSession.create(args);
        var chatScreen = new ChatScreen();
        var mode = new InteractiveMode(session);
        var dispatcher = new TuiEventDispatcher();
        // Windows Terminal/ConPTY send about one raw event per wheel notch
        // (VS Code class), so the per-notch density default is 1 instead of 3.
        var scrollConfig = ScrollConfig.from(
            session.services().settings().effective(),
            TamboUIAdapter.isWindows() ? 1 : 3);
        var app = new PiTuiApp(mode, chatScreen,
            new KeybindingsManager(), dispatcher, scrollConfig);

        // Virtual-thread events → render-thread queue (thread model §11.1).
        mode.setObservers(
            entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
            event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));

        var theme = themeFrom(args, session);
        if ("fullscreen".equalsIgnoreCase(tuiModeFrom(args, session))) {
            return runFullscreen(app, args, theme);
        }
        return runRegular(app, args, theme);
    }

    /** Fullscreen mode: alternate-screen ToolkitRunner (internal viewport + scrollbar). */
    private static int runFullscreen(PiTuiApp app, Args args, String theme) {
        try {
            var runner = TamboUIAdapter.createRunner();
            runner.styleEngine(PiTheme.engineFor(theme));
            app.start(runner);
            app.submitInitial(args);
            runner.run(app::root);
            return 0;
        } catch (Exception e) {
            // The backend may have entered the alternate screen / hidden the
            // cursor before failing; restore the main screen so this message
            // is visible.
            try {
                System.out.print("\033[?1049l\033[?25h\033[0m");
                System.out.flush();
            } catch (Throwable ignored) {
                // best-effort restore
            }
            System.err.println("TUI error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    /**
     * Regular mode (escape hatch; aligned with Codex {@code --no-alt-screen}):
     * raw-scrollback inline shell. No alternate screen, no mouse capture, no
     * custom scrollbar — the terminal's own scrollbar/scrollback scrolls the
     * transcript; only the input box and status line stay pinned at the
     * bottom. Fullscreen (alternate screen) is the default, matching Codex.
     */
    private static int runRegular(PiTuiApp app, Args args, String theme) {
        try (var shell = InlineTuiShell.create()) {
            app.applyTheme(PiTheme.engineFor(theme));
            app.startInline(shell);
            app.submitInitial(args);
            shell.run(app::onInlineEvent, app::renderInline);
            return 0;
        } catch (Exception e) {
            System.err.println("TUI error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static String tuiModeFrom(Args args, AgentSession session) {
        if (args.tuiMode() != null) {
            return args.tuiMode();
        }
        var mode = session.services().settings().effective().tuiMode;
        return mode == null ? "fullscreen" : mode;
    }

    private static String themeFrom(Args args, AgentSession session) {
        if (args.themes() != null && !args.themes().isEmpty()) {
            return args.themes().get(0);
        }
        var theme = session.services().settings().effective().theme;
        return theme == null ? "dark" : theme;
    }
}
