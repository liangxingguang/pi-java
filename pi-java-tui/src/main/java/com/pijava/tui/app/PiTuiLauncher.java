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
        // IDE consoles have no PTY (System.console() == null); the
        // pi-java.dev.forceTui property lets IDEA "Emulate terminal" setups
        // that still lack a console object run the TUI anyway.
        if (System.console() == null && !Boolean.getBoolean("pi-java.dev.forceTui")) {
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
        // 批 12 T12（裁决⑥）：pi fullscreenWheelScrollLines 数字臂 ⇒ 固定行数。
        var scrollConfig = ScrollConfig.from(
            session.services().settings().effective(),
            TamboUIAdapter.isWindows() ? 1 : 3,
            session.services().settings().uiPrefs().getFullscreenWheelScrollLines());
        var app = new PiTuiApp(mode, chatScreen,
            new KeybindingsManager(), dispatcher, scrollConfig);

        // 批 12 T7（docs/23 §3；pi :948-949 init 调用点对位）：启动 changelog
        // 比较＋回写（runner 启动前装配，welcome 后展示）。
        app.setStartupChangelog(
            com.pijava.coding.agent.core.ChangelogSupport.forDisplay(
                session.services().settings(),
                com.pijava.coding.agent.cli.Version.VERSION,
                sessionHasMessages(session)),
            session.services().settings().install().getCollapseChangelog());

        // Virtual-thread events → render-thread queue (thread model §11.1).
        mode.setObservers(
            entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
            event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));

        var theme = themeFrom(args, session);
        if ("fullscreen".equalsIgnoreCase(tuiModeFrom(args, session))) {
            return runFullscreen(app, args, theme, session);
        }
        return runRegular(app, args, theme, session);
    }

    /**
     * 批 12 T7：pi {@code state.messages.length > 0} 对位（会话条目非空即 true——
     * resume/continue 不显示 changelog；docs/23 §3 T7 注记）。
     */
    private static boolean sessionHasMessages(AgentSession session) {
        return !session.accumulatedEntries().isEmpty();
    }

    /** Fullscreen mode: alternate-screen ToolkitRunner (internal viewport + scrollbar). */
    private static int runFullscreen(PiTuiApp app, Args args, String theme,
                                     AgentSession session) {
        try {
            // 批 12 T9（pi tui.ts:1477-1484 对位）：硬件光标开关注入。
            var runner = TamboUIAdapter.createRunner(
                session.services().settings().uiPrefs().getShowHardwareCursor());
            runner.styleEngine(PiTheme.engineFor(theme));
            app.start(runner);
            app.submitInitial(args);
            runner.run(app::root);
            afterFullscreenExit(session, app.terminalCols(), System.out);
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
    private static int runRegular(PiTuiApp app, Args args, String theme,
                                  AgentSession session) {
        try (var shell = InlineTuiShell.create()) {
            app.applyTheme(PiTheme.engineFor(theme));
            app.startInline(shell);
            app.submitInitial(args);
            shell.run(app::onInlineEvent, app::renderInline);
            // 批 12 T10：regular 无 alt 屏——transcript 本就在主屏滚动回退里，
            // 两臂都不动；恢复提示同样接上（pi 非 fullscreen 也只打提示）。
            printResumeHint(session, System.out);
            return 0;
        } catch (Exception e) {
            System.err.println("TUI error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    /**
     * 批 12 T10（docs/23 §3；pi :881-887＋:4322-4328 对位）：fullscreen 正常
     * 退出两臂——resume-hint 只留提示行；transcript（默认）把对话滚动回退
     * 重绘进主屏。两臂统一接恢复提示。输出流注入（测试面）。
     */
    static void afterFullscreenExit(AgentSession session, int terminalCols,
                                    java.io.PrintStream out) {
        var uiPrefs = session.services().settings().uiPrefs();
        if (!"resume-hint".equals(uiPrefs.getFullscreenExitOutput())) {
            com.pijava.tui.util.TranscriptDump.print(
                session, terminalCols, uiPrefs.getOutputPad(), out);
        }
        printResumeHint(session, out);
    }

    /**
     * 批 12 T10（pi {@code formatResumeCommand} :339-351＋:4322-4328 对位）：
     * 仅会话已持久化时打印恢复提示（pi 另要 TTY——交互模式启动已检 console）。
     * 窄差 D64：文案按 java CLI 能力成形（pi 打印精确
     * {@code pi [--session-dir X] --session <id>}；java {@code -r} 是选择器
     * 开关、无 {@code --session <id>} 直给——选择器随批 13）。
     */
    private static void printResumeHint(AgentSession session, java.io.PrintStream out) {
        if (session.sessionFile() == null) {
            return;
        }
        out.println("To resume this session: pi-java -r");
    }

    private static String tuiModeFrom(Args args, AgentSession session) {
        if (args.tuiMode() != null) {
            return args.tuiMode();
        }
        var mode = session.services().settings().effective().tuiMode;
        return mode == null ? "fullscreen" : mode;
    }

    private static String themeFrom(Args args, AgentSession session) {
        var cli = args.themes() != null && !args.themes().isEmpty()
            ? args.themes().get(0) : null;
        var settingsTheme = session.services().settings().effective().theme;
        return PiTheme.resolveTheme(cli, settingsTheme, session.themePaths());
    }
}
