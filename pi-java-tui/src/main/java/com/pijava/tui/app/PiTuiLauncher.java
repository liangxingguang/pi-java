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
        // 批 17（T7，docs/28 §3-T7.1）：ChatScreen/dispatcher/host/UI 提前到
        // AgentSession.create 之前构造——扩展在 create 内加载，UI/mode 构造期烘入
        //（pi bindings.uiContext 对位，agent-session.ts:3260）。渲染循环未起步时
        // 扩展阻塞对话不可用（裁决⑤：明确失败）；非阻塞注入入队、首帧生效。
        var chatScreen = new ChatScreen();
        var dispatcher = new TuiEventDispatcher();
        var keybindings = new KeybindingsManager();
        // settings 尚未加载（session 未建）——externalEditor 命令源晚读 session
        //（host 的 Supplier 在对话框打开时才取值，此时 session 早已装配完）。
        var sessionRef = new java.util.concurrent.atomic.AtomicReference<AgentSession>();
        var extensionUiHost = new TuiExtensionUiHost(dispatcher, chatScreen, keybindings,
            () -> {
                var s = sessionRef.get();
                return s == null ? null
                    : s.services().settings().accessors().getExternalEditorCommand();
            });
        var extensionUI = new TuiExtensionUI(extensionUiHost);
        var session = AgentSession.create(args, extensionUI, "tui");
        sessionRef.set(session);
        var mode = new InteractiveMode(session);
        // Windows Terminal/ConPTY send about one raw event per wheel notch
        // (VS Code class), so the per-notch density default is 1 instead of 3.
        // 批 12 T12（裁决⑥）：pi fullscreenWheelScrollLines 数字臂 ⇒ 固定行数。
        var scrollConfig = ScrollConfig.from(
            session.services().settings().effective(),
            TamboUIAdapter.isWindows() ? 1 : 3,
            session.services().settings().uiPrefs().getFullscreenWheelScrollLines());
        var app = new PiTuiApp(mode, chatScreen, keybindings, dispatcher, scrollConfig);
        // 批 17（T7）：overlay 开合/外部编辑器往返/终端几何回填（晚绑定 holder）。
        app.bindExtensionUi(extensionUiHost);

        // 批 12 T7（docs/23 §3；pi :948-949 init 调用点对位）：启动 changelog
        // 比较＋回写（runner 启动前装配，welcome 后展示）。
        app.setStartupChangelog(
            com.pijava.coding.agent.core.ChangelogSupport.forDisplay(
                session.services().settings(),
                com.pijava.coding.agent.cli.Version.VERSION,
                sessionHasMessages(session)),
            session.services().settings().install().getCollapseChangelog());

        // 批 15（T2a，pi :1220-1226 对位；changelog/models.json 警告之后）：
        // 上次未通知崩溃 ⇒ 启动提示「Run /bug」。路径每调现解析（批 14 教训）。
        var crash = com.pijava.coding.agent.core.crash.CrashLog.takeUnnotified(
            com.pijava.coding.agent.core.crash.CrashLog.crashLogPath(),
            System.currentTimeMillis());
        if (crash != null) {
            app.setStartupCrashNotice("pi-java crashed on " + localizedWhen(crash.timestamp())
                + " (" + crash.message() + "). Run /bug to report it; the crash details"
                + " are attached automatically.");
        }

        // 批 15（T2b，pi :4363-4390 uncaughtCrash 对位）：未捕获异常钩——
        // 恢复终端 ⇒ stderr 打印 ⇒ 扩展提示 ⇒ 记 crash ⇒ 崩溃指引 ⇒ exit 1。
        // pi 是 prepend 独占；java 亦独占，交互模式退出时恢复旧 handler。
        var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            // pi isShuttingDown 臂对位：退出中直接 exit 1（不重入）。
            restoreTerminalSequences();
            System.err.println("pi-java exiting due to uncaughtException:");
            error.printStackTrace(System.err);
            var hint = com.pijava.coding.agent.core.crash.CrashIntegration
                .formatCrashExtensionHint(
                    com.pijava.coding.agent.core.crash.CrashLog
                        .findExtensionStackMatches(stackTraceText(error), loadedExtensions(session)));
            if (hint != null) {
                System.err.println("\n" + hint);
            }
            var record = com.pijava.coding.agent.core.crash.CrashLog.record(
                com.pijava.coding.agent.core.crash.CrashRecord.Kind.UNCAUGHT_EXCEPTION,
                error, session.sessionFile(), session.cwd(),
                com.pijava.coding.agent.core.crash.CrashLog.crashLogPath());
            if (record != null) {
                System.err.println("\n" + com.pijava.coding.agent.core.crash.CrashIntegration
                    .crashReportInstructions(session.sessionFile()));
            }
            System.exit(1);
        });

        // Virtual-thread events → render-thread queue (thread model §11.1).
        mode.setObservers(
            entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
            event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));

        var theme = themeFrom(args, session);
        try {
            if ("fullscreen".equalsIgnoreCase(tuiModeFrom(args, session))) {
                return runFullscreen(app, args, theme, session);
            }
            return runRegular(app, args, theme, session);
        } finally {
            // 交互模式退出 ⇒ 恢复旧 handler（钩的独占装配只活在本模式内）。
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
    }

    /** 批 15（T2a）：pi {@code new Date(crash.timestamp).toLocaleString()} 对位。 */
    private static String localizedWhen(String isoTimestamp) {
        try {
            var instant = java.time.Instant.parse(isoTimestamp);
            return java.time.format.DateTimeFormatter
                .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                .withLocale(java.util.Locale.getDefault())
                .format(java.time.LocalDateTime.ofInstant(instant,
                    java.time.ZoneId.systemDefault()));
        } catch (Exception e) {
            return isoTimestamp;
        }
    }

    /** 批 15（T2b）：fullscreen catch 块同序的终端恢复序列。 */
    private static void restoreTerminalSequences() {
        try {
            System.out.print("\033[?1049l\033[?25h\033[0m");
            System.out.flush();
        } catch (Throwable ignored) {
            // best-effort restore
        }
    }

    /** 批 15（T2b）：栈文本化（扩展栈匹配的操作数）。 */
    private static String stackTraceText(Throwable error) {
        var writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** 批 15（T2b/裁决②）：已安装扩展表（best-effort，崩了不能再崩）。
     *  批 19（裁决④善后）：枚举源改 resolve 装配的扩展资源路径。 */
    private static java.util.List<com.pijava.coding.agent.extension.InstalledExtension>
            loadedExtensions(AgentSession session) {
        try {
            return com.pijava.coding.agent.extension.InstalledExtension
                .fromResolvedPaths(session.extensionResourcePaths());
        } catch (Exception e) {
            return java.util.List.of();
        }
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
            if (app.consumeFatalExit()) {
                // 批 15（T2c）：pi handleFatalRuntimeError（停 TUI ⇒ exit 1；
                // 不倾倒 transcript——那不是正常退出）。
                return 1;
            }
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
            if (app.consumeFatalExit()) {
                // 批 15（T2c）：pi handleFatalRuntimeError ⇒ exit 1。
                return 1;
            }
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
        // 批 14（docs/25 T16；pi main.ts:681-683）：--use-theme 优先于 --theme
        //（--theme 的「选择」语义属 A-29 重排批 20，现维持文件加载现状）。
        var cli = args.useTheme() != null ? args.useTheme()
            : (args.themes() != null && !args.themes().isEmpty() ? args.themes().get(0) : null);
        var settingsTheme = session.services().settings().effective().theme;
        return PiTheme.resolveTheme(cli, settingsTheme, session.themePaths());
    }
}
