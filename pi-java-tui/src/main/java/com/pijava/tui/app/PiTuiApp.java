package com.pijava.tui.app;

import java.io.IOException;
import java.util.List;

import com.pijava.agent.harness.WatchHandle;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.CountdownWake;
import com.pijava.tui.util.InlineRenderContext;
import com.pijava.tui.util.InlineTuiShell;
import com.pijava.tui.util.ScrollConfig;
import com.pijava.tui.util.ScrollInputNormalizer;
import com.pijava.tui.util.ScrollbackTranscript;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.Event;

/**
 * TUI application shell (Phase 3 design §7.1).
 *
 * <p>Owns the render loop: drains cross-thread events each frame, intercepts
 * global keybindings, drives the interactive mode, and hosts modal overlays
 * (model/session/tree/settings selectors). Since the alignment refactor, it
 * also owns transcript scrolling: raw wheel/trackpad events go through the
 * {@link ScrollInputNormalizer} and keyboard navigation drives the chat
 * viewport directly (the old ListElement key/mouse handlers are gone).</p>
 *
 * <p>批 29（E1 结构债拆分，行为零变化纯移动）：启动通知（{@link StartupNotices}）、
 * 全局键分派（{@link GlobalKeyRouter}）、overlay 开合（{@link OverlayManager}）、
 * 扩展宿主接线（{@link ExtensionHostWiring}）、prompt/slash 流（{@link SlashFlowRouter}）
 * 移出成件；本类只留装配、渲染帧面与对外委托桩——public/包私有 API 签名全保留。</p>
 */
public final class PiTuiApp {

    private final InteractiveMode mode;
    private final ChatScreen chatScreen;
    private final KeybindingsManager keys;
    private final TuiEventDispatcher dispatcher;
    private final ScrollInputNormalizer normalizer;
    // 会话事件通道（pi subscribeToAgent）：唯一一处结构性接线，见 SessionEventChannel。
    private final SessionEventChannel sessionEvents;
    // 批 29（E1）：责任件（构造期仅持引用、不回调——原构造体语句序不变）。
    private final StartupNotices startupNotices;
    private final GlobalKeyRouter keyRouter;
    private final OverlayManager overlayManager;
    private final ExtensionHostWiring extensionHostWiring;
    private final SlashFlowRouter slashFlow;
    // 批 29：包私有缝——责任件现读当前会话（原 this.session 直读同义）。
    AgentSession session;
    /** 批 26（docs/37 T9）：解决后的扩展快捷键（随会话 attach/switch 重解析）。 */
    volatile java.util.List<com.pijava.coding.agent.extension.ExtensionShortcuts
        .ResolvedShortcut> extensionShortcuts = java.util.List.of();
    // 批 29：包私有缝（GlobalKeyRouter/ExtensionHostWiring 读 alt 屏在否）。
    ToolkitRunner runner;
    private WatchHandle<com.pijava.agent.harness.SessionSnapshot> snapshotHandle;
    // docs/19 §3.10（裁决⑧）：树选择器的 maxVisibleLines/水平视口数据源
    // （pi ui.terminal.rows/cols 对位）；初值 80×24，随 ResizeEvent 跟踪。
    // 批 29：包私有缝（GlobalKeyRouter 的 ResizeEvent 臂写）。
    volatile int lastTerminalRows = 24;
    volatile int lastTerminalCols = 80;
    private volatile boolean running = true;
    // 批 29：包私有缝（ExtensionHostWiring 外部编辑器往返读 inline overlay 态）。
    InlineTuiShell inlineShell;
    // inline 模式专用的 1 Hz 倒计时唤醒（fullscreen 每帧重绘，不需要）。
    private CountdownWake countdownWake;
    private ScrollbackTranscript transcript;
    private final InlineRenderContext renderContext = new InlineRenderContext();
    private static final int MAX_EDITOR_LINES = 6;
    // 批 15（T2c）：fatal marker 臂置位——launcher 退出码改 1（pi exit(1)）。
    // 批 29：包私有缝（SlashFlowRouter 的 fatal 臂置位）。
    volatile boolean fatalExit;

    /** Creates the app with the default scroll configuration. */
    public PiTuiApp(InteractiveMode mode, ChatScreen chatScreen,
                    KeybindingsManager keys, TuiEventDispatcher dispatcher) {
        this(mode, chatScreen, keys, dispatcher, ScrollConfig.defaults());
    }

    /**
     * Creates the app with an explicit scroll configuration.
     *
     * @param mode          the interactive session mode
     * @param chatScreen    the main chat screen
     * @param keys          the keybinding manager
     * @param dispatcher    the cross-thread event dispatcher
     * @param scrollConfig  scroll normalization settings
     */
    public PiTuiApp(InteractiveMode mode, ChatScreen chatScreen,
                    KeybindingsManager keys, TuiEventDispatcher dispatcher,
                    ScrollConfig scrollConfig) {
        this.mode = mode;
        this.chatScreen = chatScreen;
        this.keys = keys;
        this.dispatcher = dispatcher;
        this.normalizer = new ScrollInputNormalizer(scrollConfig);
        this.session = mode.session();
        this.slashFlow = new SlashFlowRouter(this, chatScreen, mode, dispatcher, keys);
        this.overlayManager = new OverlayManager(this, chatScreen, slashFlow);
        this.extensionHostWiring = new ExtensionHostWiring(this, chatScreen);
        this.startupNotices = new StartupNotices(this, chatScreen, dispatcher, slashFlow);
        this.keyRouter = new GlobalKeyRouter(this, chatScreen, keys, mode, dispatcher,
            normalizer, overlayManager, slashFlow);
        // 批 20（B237，裁决②）：扩展命令执行件的 SlashContext 供应器（真回调）。
        injectSlashContext(session);
        // 批 16（T4d）：工具渲染器责任链随会话注入（MessageBubble 查表消费）。
        com.pijava.tui.component.ToolRenderers.install(session.toolRenderers());
        // 批 26（docs/37 T10）：custom 消息/条目渲染器解析缝随会话注入。
        com.pijava.tui.component.CustomRenderers.install(
            session::messageRendererFor, session::entryRendererFor);
        // 批 26（docs/37 T11）：markdown 变换链随会话注入（现读供应器）。
        com.pijava.tui.component.MarkdownTransformers.install(session::markdownTransformers);
        // 批 26（docs/37 T9）：扩展快捷键随会话解析（pi setupExtensionShortcuts 对位）。
        resolveExtensionShortcuts(session);
        // 批 25（docs/36 T7）：markdown.codeBlockIndent 现读缝（现读 this.session
        // ⇒ switchSession 后新会话的设置面自然生效；E2-43 消费点）。
        com.pijava.tui.component.MarkdownTheme.installCodeBlockIndent(
            () -> session.services().settings().compaction().getMarkdownCodeBlockIndent());
        this.sessionEvents = new SessionEventChannel(dispatcher, chatScreen::onSessionEvent);
        // 批 12（docs/23）：settings 消费键 live-read 注入——先于补全目录装配
        // （completer 重建带上 maxVisible 源）；现读 this.session ⇒ switchSession
        // 后新会话的设置面自然生效。
        chatScreen.setOutputPadSource(
            () -> session.services().settings().uiPrefs().getOutputPad());
        chatScreen.setEditorPaddingSource(
            () -> session.services().settings().uiPrefs().getEditorPaddingX());
        chatScreen.setAutocompleteMaxVisibleSource(
            () -> session.services().settings().uiPrefs().getAutocompleteMaxVisible());
        chatScreen.setScrollbarModeSource(
            () -> session.services().settings().uiPrefs().getFullscreenScrollbar());
        // 批 13（docs/24 T10/T11）：showCacheMissNotices 门＋miss 价目目录源
        // （ModelKey＝provider+"/"+model，首个斜杠切分——openrouter 斜杠 id 只吃前缀）。
        chatScreen.setShowCacheMissNoticesSupplier(
            () -> session.services().settings().uiPrefs().getShowCacheMissNotices());
        chatScreen.setCacheMissTrackerModels(key -> {
            var slash = key.indexOf('/');
            return slash < 0 ? java.util.Optional.empty()
                : session.modelCatalog().find(com.pijava.ai.model.ModelId.of(
                    key.substring(0, slash), key.substring(slash + 1)));
        });
        chatScreen.setSlashCommands(slashFlow.loadSlashItems());
        chatScreen.setInterruptHint(
            KeybindingHints.keyText(keys.strokeFor(KeybindingsManager.INTERRUPT)));
        // docs/21（§3.7）：排队区 hint 行键名随绑定表取（pi getAppKeyDisplay 对位）。
        chatScreen.setDequeueHint(
            KeybindingHints.displayText(keys.strokeFor(KeybindingsManager.DEQUEUE)));
        // 批 15（T2d）：message_end 的 /bug 建议钩（pi :3586 挂点对位）。
        chatScreen.setBugReportHook(extensionHostWiring::maybeSuggestBugReport);
        chatScreen.onSubmit(slashFlow::submitPrompt);
    }

    /** Attach to a runner: register the global key handler + snapshot feed. */
    public void start(ToolkitRunner runner) {
        startupNotices.showWelcomeOnce();
        this.runner = runner;
        // 批 17（T7，裁决⑤）：渲染循环起步 ⇒ 扩展阻塞对话自此可用。
        if (extensionHostWiring.extensionUiHost() != null) {
            extensionHostWiring.extensionUiHost().markRenderLoopActive(Thread.currentThread());
        }
        runner.eventRouter().addGlobalHandler(this::onEvent);
        sessionEvents.open(session, null);
        snapshotHandle = session.watchSession();
        snapshotHandle.subscribe(snapshot ->
            dispatcher.dispatch(() -> chatScreen.updateSnapshot(snapshot)));
        maybeWarnSubscriptionAuth();   // 批 12 T13（pi :1228 init 后时机对位）
    }

    /** Attach the raw-scrollback inline shell (regular TUI mode). */
    public void startInline(InlineTuiShell shell) {
        startupNotices.showWelcomeOnce();
        this.inlineShell = shell;
        if (extensionHostWiring.extensionUiHost() != null) {
            extensionHostWiring.extensionUiHost().markRenderLoopActive(Thread.currentThread());
        }
        this.transcript = new ScrollbackTranscript(new ScrollbackTranscript.Sink() {
            @Override
            public void println(Line line) {
                shell.println(line);
            }

            @Override
            public boolean replaceLastBlock(int lineCount, List<Line> block) {
                return shell.replaceLastBlock(lineCount, block);
            }
        });
        sessionEvents.open(session, null);
        snapshotHandle = session.watchSession();
        snapshotHandle.subscribe(snapshot ->
            dispatcher.dispatch(() -> chatScreen.updateSnapshot(snapshot)));
        // Regular mode redraws on demand: async dispatches mark the shell
        // dirty, so the idle loop never repaints and the terminal scrollback
        // stays exactly where the user scrolled it.
        dispatcher.setWake(shell::markDirty);
        // ...but an on-demand redraw means the retry countdown would freeze:
        // one 1 Hz nudge while a ticking indicator is alive (fullscreen
        // recomputes it per frame instead — see CountdownWake).
        countdownWake = CountdownWake.start(chatScreen::hasTickingIndicator, shell::markDirty);
        maybeWarnSubscriptionAuth();   // 批 12 T13（pi :1228 init 后时机对位）
    }

    /** Apply the CSS theme to the inline render context. */
    public void applyTheme(StyleEngine engine) {
        renderContext.setStyleEngine(engine);
    }

    // ── 批 29（E1）：启动通知委托（本体在 {@link StartupNotices}）──

    /** 批 15（T2a，pi :1220-1226 对位）：启动崩溃提示装配（changelog 之后）。 */
    public void setStartupCrashNotice(String text) {
        startupNotices.setStartupCrashNotice(text);
    }

    /** 批 12 T7（docs/23 §3）：启动 changelog 装配（welcome 之后展示一次）。 */
    public void setStartupChangelog(java.util.Optional<String> markdown, boolean collapse) {
        startupNotices.setStartupChangelog(markdown, collapse);
    }

    /** Submit initial CLI messages when the TUI starts. */
    public void submitInitial(Args args) {
        startupNotices.submitInitial(args);
    }

    /**
     * Per-frame root widget: drains queued observer events, flushes the scroll
     * normalizer (trackpad fractional rows), then renders the screen tree.
     */
    public Element root() {
        dispatcher.drain();
        var update = normalizer.onTick(System.currentTimeMillis());
        if (update.lines() != 0) {
            chatScreen.scrollByRows(update.lines());
        }
        // 批 17（T4/T5）：扩展对话框的程序式完成（倒计时到期/abort/会话复位）
        // 不走键路径——帧级收口。
        overlayManager.closeOverlayIfDone();
        var activeOverlay = overlayManager.currentOverlay();
        if (activeOverlay != null) {
            return TamboUIAdapter.column(
                chatScreen.render().fill(),
                activeOverlay.render(),
                chatScreen.statusBar()).addClass("Screen");
        }
        return TamboUIAdapter.column(
            chatScreen.render().fill(),
            chatScreen.statusBar()).addClass("Screen");
    }

    /** Per-event inline handler: same routing as onEvent, then resizes the bottom region. */
    void onInlineEvent(Event event) {
        onEvent(event);
        updateBottomHeight();
    }

    /**
     * Per-frame inline renderer: the fixed bottom region (editor + status) or,
     * while a modal is open, the full-screen overlay inside the alternate screen.
     */
    void renderInline(Frame frame) {
        dispatcher.drain();
        overlayManager.closeOverlayIfDone();   // 批 17（T4/T5）：inline 同帧级收口
        if (overlayManager.currentOverlay() != null) {
            renderOverlayInline();
            return;
        }
        if (inlineShell.overlayActive()) {
            try {
                inlineShell.endOverlay();
            } catch (IOException e) {
                // best-effort; retried next frame
            }
        }
        transcript.sync(chatScreen.transcriptMessages(), chatScreen.transcriptDraft(),
            inlineShell.width());
        chatScreen.renderBottomArea().render(frame, frame.area(), renderContext);
    }

    private void renderOverlayInline() {
        if (!inlineShell.overlayActive()) {
            try {
                inlineShell.beginOverlay();
            } catch (IOException e) {
                return;
            }
        }
        Element root = overlayManager.currentOverlay().render();
        inlineShell.renderOverlay(f -> root.render(f, f.area(), renderContext));
    }

    private void updateBottomHeight() {
        if (inlineShell == null || overlayManager.currentOverlay() != null) {
            return;
        }
        int editorLines = Math.min(Math.max(1, chatScreen.editorLineCount()), MAX_EDITOR_LINES);
        // 批 17（T6）：扩展注入层（header/widget/状态段/自定义 footer）占位行数。
        inlineShell.setContentHeight(
            chatScreen.completerLineCount() + editorLines + 1
                + chatScreen.extensionAreaLineCount());
    }

    public boolean isRunning() {
        return running;
    }

    /** 斜杠目录加载委托（批 29 拆分；反射夹具 TemplateCommandsTuiTest 锚定本签名）。 */
    java.util.List<com.pijava.tui.component.SlashCompleter.CommandItem> loadSlashItems() {
        return slashFlow.loadSlashItems();
    }

    /** 全局事件分派（批 29 起本体在 {@link GlobalKeyRouter}——逐臂序未动）。 */
    private EventResult onEvent(Event event) {
        return keyRouter.onEvent(event);
    }

    // ── 批 29（E1）：overlay 委托（本体在 {@link OverlayManager}）──

    void openOverlay(ScreenOverlay screenOverlay) {
        overlayManager.openOverlay(screenOverlay);
    }

    /** 批 17（T4/T5）：扩展对话框的程序式关闭（abort/timeout/会话复位路径）。 */
    void dismissOverlay(ScreenOverlay target) {
        overlayManager.dismissOverlay(target);
    }

    /** The current modal overlay, or null (test hook). */
    ScreenOverlay currentOverlay() {
        return overlayManager.currentOverlay();
    }

    // ── 批 29（E1）：扩展宿主接线委托（本体在 {@link ExtensionHostWiring}）──

    /** 批 21（T9）：回填主题控制器（launcher 在会话装配后注入）。 */
    public void setThemeController(
            com.pijava.coding.agent.theme.ThemeController controller) {
        extensionHostWiring.setThemeController(controller);
    }

    /** 批 21（T11）：主题控制器读面（设置页 Theme 子菜单用；可空＝未装配）。 */
    public com.pijava.coding.agent.theme.ThemeController themeController() {
        return extensionHostWiring.themeController();
    }

    /** 批 17（T7，docs/28 §3-T7.1）：回填扩展 UI 宿主（晚绑定 holder）。 */
    public void bindExtensionUi(TuiExtensionUiHost host) {
        extensionHostWiring.bindExtensionUi(host);
    }

    /** pi 四触发时机的统一入口（:1228/:4550/:6120/:5352；每会话一次，guard 去重）。 */
    void maybeWarnSubscriptionAuth() {
        extensionHostWiring.maybeWarnSubscriptionAuth();
    }

    /** 凭证探针注入（T13 测试面——不碰真实 env/凭证库）。 */
    void setCredentialProbe(java.util.function.Supplier<
            java.util.Optional<com.pijava.ai.auth.RecordedCredential>> probe) {
        extensionHostWiring.setCredentialProbe(probe);
    }

    /** 双击时间窗的时钟注入（测试面；生产 System::currentTimeMillis）。 */
    void setNowMillis(java.util.function.LongSupplier supplier) {
        keyRouter.setNowMillis(supplier);
    }

    /** 终端行数（ResizeEvent 跟踪，初值 24——裁决⑧）。 */
    int terminalRows() {
        return lastTerminalRows;
    }

    /** 终端列数（ResizeEvent 跟踪，初值 80）。 */
    int terminalCols() {
        return lastTerminalCols;
    }

    /**
     * 批 20（B237，裁决②）：扩展命令执行件的 {@link SlashContext} 供应器注入
     * （真 quit/switch 回调；供应器捕获目标会话自身——替换后旧供应器随旧会话弃）。
     */
    private void injectSlashContext(AgentSession target) {
        target.slashContextSupplier(
            () -> new SlashContext(target, keys, this::exit, this::switchSession));
    }

    /**
     * 批 26（docs/37 T9）：pi {@code setupExtensionShortcuts} 对位——按会话解析
     * 扩展快捷键（冲突三规则在 {@code AgentSession.extensionShortcuts} 内）；
     * 诊断上屏（pi 的 TUI 诊断面方言——pi 走 shortcutDiagnostics 数组，java 经
     * 启动诊断同款 appendSystemText 通道）。
     */
    private void resolveExtensionShortcuts(AgentSession target) {
        var resolution = target.extensionShortcuts(keys);
        extensionShortcuts = resolution.shortcuts();
        for (var diagnostic : resolution.diagnostics()) {
            chatScreen.appendSystemText(diagnostic);
        }
    }

    /** Visible for testing (docs/16 ExitPathCloseTest 的替换臂驱动口)。 */
    void switchSession(AgentSession newSession) {
        // 批 17 评审回补（Important #2）：模式层扩展 UI/mode 随切换携带——pi
        // finishSessionReplacement→rebindSession（agent-session-runtime.ts:187-191）
        // 对位；/new（createReplacing 烘 noop）、/fork、/resume 全会话换都过此缝。
        newSession.extensionUI(session.extensionUI(), session.extensionMode());
        // 批 20（B237）：SlashContext 供应器同缝重注（批 17 评审 #2 同族）。
        injectSlashContext(newSession);
        this.session = newSession;
        // 批 16（T4d）：渲染器注册表随会话切换重装。
        com.pijava.tui.component.ToolRenderers.install(newSession.toolRenderers());
        // 批 26（docs/37 T10）：custom 渲染器解析缝随会话重装。
        com.pijava.tui.component.CustomRenderers.install(
            newSession::messageRendererFor, newSession::entryRendererFor);
        // 批 26（docs/37 T11）：markdown 变换链随会话重装。
        com.pijava.tui.component.MarkdownTransformers.install(
            newSession::markdownTransformers);
        // 批 26（docs/37 T9）：扩展快捷键随会话重解析。
        resolveExtensionShortcuts(newSession);
        mode.switchSession(newSession);
        // 批 12 T13：警告 guard 随会话重建（pi anthropicSubscriptionWarningShown
        // 每会话一次）。
        extensionHostWiring.renewSubscriptionWarning();
        // 会话事件通道与快照订阅都跟着换会话重接（各自先摘旧句柄）。
        sessionEvents.open(newSession, null);
        if (snapshotHandle != null) {
            snapshotHandle.close();
        }
        snapshotHandle = newSession.watchSession();
        snapshotHandle.subscribe(snapshot ->
            dispatcher.dispatch(() -> chatScreen.updateSnapshot(snapshot)));
    }

    /** Visible for testing（docs/16 ExitPathCloseTest 的退出驱动口）。 */
    void exit() {
        running = false;
        sessionEvents.close();
        if (countdownWake != null) {
            countdownWake.close();
            countdownWake = null;
        }
        if (inlineShell != null) {
            inlineShell.quit();
        }
        if (runner != null) {
            runner.quit();
        }
        // docs/16（B252）：pi interactive shutdown 交互臂（interactive-mode.ts:4316-4322）
        // —— 先停 UI 再 dispose（shutdown{quit} 自此在 TUI 发射）。关的是**当前**会话
        // （this.session 随 switchSession 跟踪替换）。
        try {
            session.close();
        } catch (Exception ignored) {
            // 退出期关闭失败忽略（pi dispose 的 try/catch 同旨，agent-session.ts:1394-1402）
        }
    }

    /** The session-event channel (test hook). */
    SessionEventChannel sessionEvents() {
        return sessionEvents;
    }

    /**
     * 批 15（T2c）：fatal 退出一次性读取（launcher 据此把退出码改 1——pi
     * {@code process.exit(1)} 对位）。
     */
    boolean consumeFatalExit() {
        var was = fatalExit;
        fatalExit = false;
        return was;
    }
}
