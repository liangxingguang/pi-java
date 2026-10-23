package com.pijava.tui.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.harness.WatchHandle;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AnthropicExtraUsageWarning;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.PromptTemplateItems;
import com.pijava.coding.agent.core.slash.SkillCommandItems;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.component.SlashCompleter;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ModelSelectorScreen;
import com.pijava.tui.screen.ModelThinkingLevelScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.screen.SessionListScreen;
import com.pijava.tui.screen.SettingsScreen;
import com.pijava.tui.screen.UserMessageSelectorScreen;
import com.pijava.tui.screen.WelcomeOverlay;
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
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;

/**
 * TUI application shell (Phase 3 design §7.1).
 *
 * <p>Owns the render loop: drains cross-thread events each frame, intercepts
 * global keybindings, drives the interactive mode, and hosts modal overlays
 * (model/session/tree/settings selectors). Since the alignment refactor, it
 * also owns transcript scrolling: raw wheel/trackpad events go through the
 * {@link ScrollInputNormalizer} and keyboard navigation drives the chat
 * viewport directly (the old ListElement key/mouse handlers are gone).</p>
 */
public final class PiTuiApp {

    private final InteractiveMode mode;
    private final ChatScreen chatScreen;
    private final KeybindingsManager keys;
    private final TuiEventDispatcher dispatcher;
    private final ScrollInputNormalizer normalizer;
    // 会话事件通道（pi subscribeToAgent）：唯一一处结构性接线，见 SessionEventChannel。
    private final SessionEventChannel sessionEvents;
    private AgentSession session;
    private ToolkitRunner runner;
    private WatchHandle<com.pijava.agent.harness.SessionSnapshot> snapshotHandle;
    // Written on the render thread (dispatcher drain) and read by the global
    // key handler plus test hooks on other threads — needs volatile visibility.
    private volatile ScreenOverlay overlay;
    // docs/19 §3.10（裁决⑧）：树选择器的 maxVisibleLines/水平视口数据源
    // （pi ui.terminal.rows/cols 对位）；初值 80×24，随 ResizeEvent 跟踪。
    private volatile int lastTerminalRows = 24;
    private volatile int lastTerminalCols = 80;
    private volatile boolean running = true;
    private InlineTuiShell inlineShell;
    // inline 模式专用的 1 Hz 倒计时唤醒（fullscreen 每帧重绘，不需要）。
    private CountdownWake countdownWake;
    private ScrollbackTranscript transcript;
    private boolean welcomeShown;
    private final InlineRenderContext renderContext = new InlineRenderContext();
    private static final int MAX_EDITOR_LINES = 6;
    // 批 12 T2（docs/23 §3）：双击 Esc 的 500ms 时间窗（pi lastEscapeTime 对位）。
    private long lastEscapeAt;
    private java.util.function.LongSupplier nowMillis = System::currentTimeMillis;
    // 批 12 T13：Anthropic 订阅凭证警告（每会话一次——随 switchSession 重建）。
    private AnthropicExtraUsageWarning subscriptionWarning;
    /** 凭证解析闭包（生产＝{@code Credentials.resolveCredential}；测试注入）。 */
    private java.util.function.Supplier<
            java.util.Optional<com.pijava.ai.auth.RecordedCredential>> credentialProbe =
        () -> com.pijava.ai.auth.Credentials.resolveCredential("anthropic");
    // 批 12 T7：启动 changelog（runInteractive 装配线程写入，渲染线程
    // showWelcomeOnce 读取 ⇒ volatile 跨线程可见性，同 lastTerminalRows 先例）。
    private volatile String startupChangelog;
    private volatile boolean startupChangelogCollapse;
    // 批 15（T2a，pi :1220-1226）：启动崩溃提示（同 startupChangelog 装配形）。
    private volatile String startupCrashNotice;
    // 批 15（T2d，pi bugReportHintShown）：/bug 建议每进程最多一次。
    private volatile boolean bugReportHintShown;
    // 批 15（T2c）：fatal marker 臂置位——launcher 退出码改 1（pi exit(1)）。
    private volatile boolean fatalExit;

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
        // 批 16（T4d）：工具渲染器责任链随会话注入（MessageBubble 查表消费）。
        com.pijava.tui.component.ToolRenderers.install(session.toolRenderers());
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
        subscriptionWarning = newSubscriptionWarning();
        chatScreen.setSlashCommands(loadSlashItems());
        chatScreen.setInterruptHint(
            KeybindingHints.keyText(keys.strokeFor(KeybindingsManager.INTERRUPT)));
        // docs/21（§3.7）：排队区 hint 行键名随绑定表取（pi getAppKeyDisplay 对位）。
        chatScreen.setDequeueHint(
            KeybindingHints.displayText(keys.strokeFor(KeybindingsManager.DEQUEUE)));
        // 批 15（T2d）：message_end 的 /bug 建议钩（pi :3586 挂点对位）。
        chatScreen.setBugReportHook(this::maybeSuggestBugReport);
        chatScreen.onSubmit(this::submitPrompt);
    }

    /** Build the completion catalog from the slash-command registry. */
    private List<SlashCompleter.CommandItem> loadSlashItems() {
        var registry = session.services().slashCommands();
        var items = new ArrayList<>(registry.names().stream()
            .map(name -> {
                var command = registry.get(name);
                return new SlashCompleter.CommandItem(
                    name, command.argumentHint(), command.description());
            })
            .toList());
        // B247（pi interactive-mode.ts:787-792）：模板臂——无条件（pi 无门，
        // 对照 skill 臂的开关），序在内置之后、skill 之前（pi :820-823
        // 内置→模板→扩展→skill；java 无扩展臂，B221）。
        for (var entry : PromptTemplateItems.list(session.services().promptTemplates().all())) {
            items.add(new SlashCompleter.CommandItem(
                entry.name(), entry.argumentHint(), entry.description()));
        }
        // B244（pi interactive-mode.ts:803-815）：开关开 ⇒ 每个技能一个
        // skill:<name> 补全条目，排在模板之后。
        var enabled = session.services().settings().accessors().getEnableSkillCommands();
        for (var entry : SkillCommandItems.list(session.harness().skillManager().all(), enabled)) {
            items.add(new SlashCompleter.CommandItem(entry.name(), null, entry.description()));
        }
        return List.copyOf(items);
    }

    /** Attach to a runner: register the global key handler + snapshot feed. */
    public void start(ToolkitRunner runner) {
        showWelcomeOnce();
        this.runner = runner;
        // 批 17（T7，裁决⑤）：渲染循环起步 ⇒ 扩展阻塞对话自此可用。
        if (extensionUiHost != null) {
            extensionUiHost.markRenderLoopActive(Thread.currentThread());
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
        showWelcomeOnce();
        this.inlineShell = shell;
        if (extensionUiHost != null) {
            extensionUiHost.markRenderLoopActive(Thread.currentThread());
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

    /** Emit the startup banner exactly once (fullscreen or inline launch). */
    private void showWelcomeOnce() {
        if (welcomeShown) {
            return;
        }
        welcomeShown = true;
        chatScreen.showWelcome(new WelcomeOverlay(session).text());
        showStartupChangelog();
        showStartupCrashNotice();
        showStartupDiagnostics();
    }

    /** 批 16（T1d 裁决⑧）：扩展 flag 绑定诊断随启动提示展示（dim 系统文本）。 */
    private void showStartupDiagnostics() {
        for (var diagnostic : session.startupDiagnostics()) {
            chatScreen.appendSystemText(diagnostic.type() + ": " + diagnostic.message());
        }
    }

    /**
     * 批 15（T2a，pi :1220-1226 对位）：启动崩溃提示装配（changelog 之后）。
     * 裁决⑪：走 dim 系统文本（pi 是 warning 色 ThemedText，窄差 D83）。
     */
    public void setStartupCrashNotice(String text) {
        this.startupCrashNotice = text;
    }

    private void showStartupCrashNotice() {
        if (startupCrashNotice == null) {
            return;
        }
        var text = startupCrashNotice;
        startupCrashNotice = null;
        chatScreen.appendSystemText(text);
    }

    /**
     * 批 12 T7（docs/23 §3）：启动 changelog 装配（runInteractive 在 runner
     * 启动前调用；welcome 之后、dispatcher 首帧前展示一次）。
     */
    public void setStartupChangelog(java.util.Optional<String> markdown, boolean collapse) {
        this.startupChangelog = markdown.orElse(null);
        this.startupChangelogCollapse = collapse;
    }

    /** pi {@code showStartupNoticesIfNeeded}（:843-872）两臂对位。 */
    private void showStartupChangelog() {
        if (startupChangelog == null) {
            return;
        }
        var markdown = startupChangelog;
        startupChangelog = null;
        if (startupChangelogCollapse) {
            // pi 折叠臂一行（文案逐字【模型可见】）。
            var v = com.pijava.coding.agent.core.ChangelogSupport
                .latestVersionOf(markdown).orElse(Version.VERSION);
            chatScreen.appendSystemText(
                "Updated to v" + v + ". Use /changelog to view full changelog.");
        } else {
            // pi 非折叠臂："What's New" 标题＋正文（D63：纯文本渲染，Markdown
            // 组件随 R1 批）。
            chatScreen.appendSystemText("What's New\n\n" + markdown.trim());
        }
    }

    /** Submit initial CLI messages when the TUI starts. */
    public void submitInitial(Args args) {
        if (args.messages() != null && !args.messages().isEmpty()) {
            submitPrompt(String.join(" ", args.messages()));
        }
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
        closeOverlayIfDone();
        if (overlay != null) {
            return TamboUIAdapter.column(
                chatScreen.render().fill(),
                overlay.render(),
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
        closeOverlayIfDone();   // 批 17（T4/T5）：inline 同帧级收口
        if (overlay != null) {
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
        Element root = overlay.render();
        inlineShell.renderOverlay(f -> root.render(f, f.area(), renderContext));
    }

    private void updateBottomHeight() {
        if (inlineShell == null || overlay != null) {
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

    private EventResult onEvent(Event event) {
        if (event instanceof TickEvent) {
            // Redraw on every tick so streamed text (dispatched from the
            // virtual-thread observer) reaches the draft inside the viewport.
            return EventResult.HANDLED;
        }
        if (event instanceof ResizeEvent resizeEvent) {
            lastTerminalCols = resizeEvent.width();
            lastTerminalRows = resizeEvent.height();
            return EventResult.HANDLED;
        }
        if (event instanceof KeyEvent keyEvent) {
            return onKeyEvent(keyEvent);
        }
        if (event instanceof PasteEvent pasteEvent) {
            chatScreen.insertText(pasteEvent.text());
            return EventResult.HANDLED;
        }
        if (event instanceof MouseEvent mouseEvent) {
            return onMouseEvent(mouseEvent);
        }
        return EventResult.UNHANDLED;
    }

    /**
     * Global mouse handler: scroll events feed the normalizer first; once the
     * global handler consumes them, routed elements never see them.
     */
    private EventResult onMouseEvent(MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP
                || event.kind() == MouseEventKind.SCROLL_DOWN) {
            int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            int lines = normalizer.onEvent(direction, System.currentTimeMillis());
            if (lines != 0) {
                chatScreen.scrollByRows(lines);
            }
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private EventResult onKeyEvent(KeyEvent event) {
        if (overlay != null) {
            overlay.onKeyEvent(event);
            closeOverlayIfDone();
            return EventResult.HANDLED;
        }
        if (TamboUIAdapter.isSendEnter(event)) {
            chatScreen.applyCompletion(); // Enter picks the highlighted command
            submit();
            return EventResult.HANDLED;
        }
        if (TamboUIAdapter.isNewlineEnter(event)) {
            chatScreen.insertNewline();
            return EventResult.HANDLED;
        }
        var keyId = keys.resolve(TamboUIAdapter.toStroke(event));
        if (keyId != null) {
            if (KeybindingsManager.INTERRUPT.equals(keyId)
                    && chatScreen.completerActive()) {
                // Esc closes the completion popup before it can interrupt.
                chatScreen.onKeyEvent(event);
                return EventResult.HANDLED;
            }
            handleAction(keyId);
            return EventResult.HANDLED;
        }
        // Tab is surfaced as CHAR('\t') (see EventParser); use it for slash
        // completion when the panel is open, otherwise ignore it so the editor
        // never receives a stray tab character.
        if (event.isChar('\t') && !event.hasShift()) {
            if (chatScreen.completerActive()) {
                chatScreen.onKeyEvent(event);
            }
            return EventResult.HANDLED;
        }
        // Empty editor: navigation keys scroll the transcript directly.
        if (chatScreen.isInputEmpty() && isChatNavigation(event)) {
            scrollByKey(event);
            return EventResult.HANDLED;
        }
        chatScreen.onKeyEvent(event);
        return EventResult.HANDLED;
    }

    private static boolean isChatNavigation(KeyEvent event) {
        if (event.hasCtrl() || event.hasAlt() || event.hasShift()) {
            return false;
        }
        return switch (event.code()) {
            case KeyCode.UP, KeyCode.DOWN, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN,
                 KeyCode.HOME, KeyCode.END -> true;
            default -> false;
        };
    }

    private void scrollByKey(KeyEvent event) {
        switch (event.code()) {
            case KeyCode.UP -> chatScreen.scrollByRows(-1);
            case KeyCode.DOWN -> chatScreen.scrollByRows(1);
            case KeyCode.PAGE_UP -> chatScreen.scrollByRows(-chatScreen.visibleRows());
            case KeyCode.PAGE_DOWN -> chatScreen.scrollByRows(chatScreen.visibleRows());
            case KeyCode.HOME -> chatScreen.scrollToTop();
            case KeyCode.END -> chatScreen.scrollToBottom();
            default -> { /* unreachable for isChatNavigation events */ }
        }
    }

    /**
     * Submit a prompt through the interactive mode and queue the inter-turn
     * separator (Codex-CLI style) once the run status completes, so the
     * divider lands after the committed transcript entries.
     */
    private void submitPrompt(String text) {
        chatScreen.resetRunTracking();
        long startNanos = System.nanoTime();
        var result = mode.submit(text);
        // A5（原 docs/25）：运行中提交仅排队，没有 run 收尾 ⇒ 不画 run 分隔线。
        if ("queued".equals(result.disposition())) {
            return;
        }
        result.statusFuture().thenAccept(status ->
            dispatcher.dispatch(() ->
                chatScreen.finishRun(System.nanoTime() - startNanos)));
    }
    private void submit() {
        var text = chatScreen.inputText();
        if (text.isBlank()) {
            return;
        }
        var slashContext = new SlashContext(
            session, keys, this::exit, this::switchSession);
        var command = mode.dispatch(text, slashContext);
        if (command != null) {
            chatScreen.clearInput();
            boolean loginCommand = text.startsWith("/login");
            // 批 15（T9，pi :5207 直设臂的 auth 警告时机对位——guard 内部去重，
            // 未直设（开选择器）也调用无害）。
            boolean modelCommand = text.startsWith("/model ") || text.equals("/model");
            command.thenAccept(result ->
                dispatcher.dispatch(() -> {
                    handleCommandResult(result, text);
                    // 批 12 T13（pi :5207/:5352 登录后时机对位）。
                    if (loginCommand || modelCommand) {
                        maybeWarnSubscriptionAuth();
                    }
                }));
        } else {
            chatScreen.appendUserText(text);
            chatScreen.submitInput();
        }
    }

    private void handleCommandResult(String result) {
        handleCommandResult(result, null);
    }

    /**
     * @param input 原始输入行（裁决⑦：marker 不携载荷，{@code /bug} 的 hint
     *              由此切——pi :3242-3245 对位）
     */
    private void handleCommandResult(String result, String input) {
        if (result == null) {
            return;
        }
        // 批 15（T2c，pi handleFatalRuntimeError :2113-2126 对位）：致命错误——
        // showError ⇒ 崩溃指引（crash 已记 ⇒ 恒打）⇒ 停 TUI ⇒ 退出码 1。
        if (result.startsWith(CommandRegistry.UI_FATAL_EXIT_PREFIX)) {
            chatScreen.showError(
                result.substring(CommandRegistry.UI_FATAL_EXIT_PREFIX.length()));
            chatScreen.appendSystemText(
                com.pijava.coding.agent.core.crash.CrashIntegration
                    .crashReportInstructions(session.sessionFile()));
            fatalExit = true;
            exit();
            return;
        }
        switch (result) {
            case CommandRegistry.UI_MODEL_SELECTOR ->
                openOverlay(new ModelSelectorScreen(session));
            case CommandRegistry.UI_SESSION_SELECTOR ->
                openOverlay(new SessionListScreen(session.listSessions()));
            case CommandRegistry.UI_TREE_SELECTOR -> openTreeSelector();
            case CommandRegistry.UI_FORK_SELECTOR -> openForkSelector();
            case CommandRegistry.UI_SETTINGS ->
                openOverlay(new SettingsScreen(session));
            case CommandRegistry.UI_SCOPED_MODELS ->
                chatScreen.appendSystemText(
                    "Use /scoped-models +<model> or /scoped-models -<model>");
            // 批 15（T8）：/thinking（空参）的选择器（裁决⑥简版）。
            case CommandRegistry.UI_THINKING_SELECTOR ->
                openOverlay(new com.pijava.tui.screen.ThinkingLevelScreen(
                    session, chatScreen::appendSystemText));
            // 批 15（T7）：/bug 故障上报流——hint 从原始输入行切（pi :3242-3245）。
            case CommandRegistry.UI_BUG_REPORT -> {
                var hint = input != null && input.length() > 4
                    ? input.substring(4).trim() : "";
                new BugReportFlow(session, chatScreen, dispatcher, this::openOverlay)
                    .begin(hint.isEmpty() ? null : hint);
            }
            default -> chatScreen.appendSystemText(result);
        }
    }

    /**
     * /tree 入口（批 12 T2 抽出，双击 Esc 与 slash 两路共用）：
     * docs/18（§3.8/§3.9）＋ docs/19（§3.9）——原地导航流（entry 树选择器 →
     * 选 entry 后三选摘要）。
     */
    private void openTreeSelector() {
        new TreeNavigationFlow(session, mode, chatScreen, dispatcher,
            this::openOverlay, this::terminalRows, this::terminalCols, keys)
            .begin();
    }

    /**
     * /fork 入口（批 12 T2 抽出；docs/21 T3，B254）：pi
     * {@code showUserMessageSelector}（interactive-mode.ts:5500-5538 对位）——
     * user-message 选择器。
     */
    private void openForkSelector() {
        var forkMessages = session.getUserMessagesForForking();
        if (forkMessages.isEmpty()) {                       // pi :5503-5506
            chatScreen.appendSystemText("No messages to fork from");
            return;
        }
        var initialSelectedId = forkMessages.get(forkMessages.size() - 1).entryId();
        openOverlay(new UserMessageSelectorScreen(forkMessages, initialSelectedId,
            this::terminalCols,
            entryId -> forkFromMessage(entryId, forkMessages),
            () -> { }));                                // Esc：pi 的 onCancel 只关选择器
    }

    /**
     * pi {@code /fork} 选择器的 onSelect 回调（interactive-mode.ts:5514-5528 逐字序，
     * docs/21 §3.3）：fork ⇒ 切换会话 ⇒ 选中消息文本回填编辑器 ⇒ 状态文案。
     */
    private void forkFromMessage(String entryId, List<AgentSession.ForkMessage> messages) {
        try {
            var forked = session.forkFromEntry(entryId);    // position "before"（批 5/6）
            if (forked.isEmpty()) {
                // session_before_fork cancel ⇒ pi {cancelled}：仅 requestRender（:5517-5520）。
                return;
            }
            switchSession(forked.get());
            // pi :5522：editor.setText(selectedText ?? "")——selectedText＝选中消息 text
            // （与 fork 消息表同一条目，裁决②）。
            var selectedText = messages.stream()
                .filter(m -> m.entryId().equals(entryId))
                .map(AgentSession.ForkMessage::text).findFirst().orElse("");
            chatScreen.setEditorText(selectedText);
            chatScreen.appendSystemText("Forked to new session");
        } catch (Exception e) {                             // pi :5526-5527
            chatScreen.showError(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    private void handleAction(String keyId) {
        switch (keyId) {
            case KeybindingsManager.INTERRUPT -> {
                // docs/21（T5，D41 重裁）：pi onEscape 首臂（:3071-3073）——流式中
                // Esc 先把排队消息回填编辑器再 abort（pi 的 {abort:true} 在 restore 内
                // 触发；java 拆开等价：restore 同步清队列，abort 紧随其后无交错窗口）。
                if (session.isRunning()) {
                    var restored = mode.restoreQueuedMessages();
                    if (restored != null) {
                        chatScreen.restoreToEditor(restored.combinedText());
                    }
                    mode.abort();
                    // 批 12 T2：流式中断消费本次 Esc，不计双击（pi :3071-3073 短路）。
                    lastEscapeAt = 0L;
                } else if (chatScreen.isInputEmpty()) {
                    // 批 12 T2（docs/23 §3；pi :3067-3097 对位）：空编辑器双击 Esc
                    // （500ms 窗）⇒ doubleEscapeAction（默认 "tree"）。
                    var action =
                        session.services().settings().accessors().getDoubleEscapeAction();
                    if (!"none".equals(action)) {
                        long now = nowMillis.getAsLong();
                        if (now - lastEscapeAt < 500) {
                            if ("tree".equals(action)) {
                                openTreeSelector();
                            } else {
                                openForkSelector();
                            }
                            lastEscapeAt = 0L;
                        } else {
                            lastEscapeAt = now;
                        }
                    }
                } else {
                    mode.abort();   // 既有：非空编辑器/空闲 abort（幂等）
                }
            }
            case KeybindingsManager.FOLLOW_UP -> {
                if (chatScreen.isInputEmpty()) {
                    break;
                }
                var text = chatScreen.inputText();
                // A5：pi Alt+Enter —— 运行中才排 followUp；空闲等同普通 Enter
                // （interactive-mode.ts:4418-4429）。
                // B175（原 docs/26）：压缩中 ⇒ 缓冲为 followUp。
                if (session.isCompacting() || session.isRunning()) {
                    mode.followUp(text);
                } else {
                    submitPrompt(text);
                }
                chatScreen.clearInput();
            }
            case KeybindingsManager.CLEAR -> chatScreen.clearInput();
            case KeybindingsManager.EXIT -> {
                if (chatScreen.isInputEmpty()) {
                    exit();
                }
            }
            case KeybindingsManager.MODEL_SELECT ->
                openOverlay(new ModelSelectorScreen(session));
            // B137：Ctrl+P 在 --models/settings scope 内正向轮转（空 scope 走全目录）。
            // TUI 无模型常驻标签，切换静默——与 Ctrl+L 选择器现状一致。
            case KeybindingsManager.MODEL_CYCLE -> {
                session.cycleModelForward();
                maybeWarnSubscriptionAuth();   // 批 12 T13（pi :6120/:6126 切换时机对位）
            }
            // 批 12 T3（docs/23 §3；pi :4596-4611 对位）：Ctrl+G 外部编辑器往返。
            case KeybindingsManager.EXTERNAL_EDITOR -> openExternalEditor();
            case KeybindingsManager.DEQUEUE -> {
                // docs/21（T4，S20）：pi handleDequeue（:4507-4513）——全部排队
                // （引擎两队列＋压缩缓冲）合并取回编辑器，文案逐字【模型可见】。
                var restored = mode.restoreQueuedMessages();
                if (restored == null) {
                    chatScreen.appendSystemText("No queued messages to restore");
                } else {
                    chatScreen.restoreToEditor(restored.combinedText());
                    chatScreen.appendSystemText("Restored " + restored.count()
                        + " queued message" + (restored.count() > 1 ? "s" : "") + " to editor");
                }
            }
            default -> { /* thinking.* / tools.expand → Phase 6 */ }
        }
    }

    /**
     * 批 12 T3（docs/23 §3；pi {@code handleOpenExternalEditor} :4596-4611 对位）：
     * 编辑器内容写临时文件 ⇒ 外部编辑器（inheritIO）⇒ 退出码 0 读回。
     * alt 屏模式先退后进（pi ui.stop()/ui.start()）；渲染线程阻塞在等待上，
     * 子进程输出与 TUI 帧由 alt 屏序列隔离。
     */
    private void openExternalEditor() {
        var command = session.services().settings().accessors().getExternalEditorCommand();
        var content = chatScreen.inputText();
        boolean altScreen = runner != null;   // regular 内联模式无 alt 屏 ⇒ 空操作
        if (altScreen) {
            TamboUIAdapter.leaveAlternateScreen();
        }
        try {
            var result =
                com.pijava.coding.agent.core.ExternalEditorSupport.edit(command, content);
            if (result instanceof com.pijava.coding.agent.core.ExternalEditorSupport
                    .Result.Complete(var text)) {
                chatScreen.setEditorText(text);
            }
        } catch (Exception e) {
            chatScreen.showError("External editor failed: " + e.getMessage());
        } finally {
            if (altScreen) {
                TamboUIAdapter.enterAlternateScreen();
            }
        }
    }

    /**
     * overlay 结局收口（键路径与帧路径共用——批 17 起倒计时/abort/会话复位在
     * 键路径外完成）。docs/18：先清位再 apply——apply 可链式开下一个 overlay
     * （树导航流：树选择器→三选→编辑器），旧序会把它立即抹掉。
     */
    private void closeOverlayIfDone() {
        var done = overlay;
        if (done == null || !done.isDone()) {
            return;
        }
        overlay = null;
        done.apply(session, this::switchSession);
        // 批 12 T14：设置屏的 thinking-level 子菜单请求 ⇒ 不关屏改开
        // ModelThinkingLevelScreen（pi settings-selector.ts:607-610 对位）。
        if (done instanceof SettingsScreen settings
                && settings.pollModelThinkingRequest()) {
            openOverlay(new ModelThinkingLevelScreen(session));
            return;
        }
        // 批 13 T9（pi :5045-5048 的 rebuildChatFromMessages 对位）：
        // "Cache miss notices" 改动 ⇒ 整表重建（重推导 miss 通知/暖行）。
        if (done instanceof SettingsScreen settings
                && settings.pollCacheMissNoticesToggled()) {
            chatScreen.rebuildTranscript(session.accumulatedEntries(),
                session.harness().snapshot(session.laneName()).leafId());
        }
        // 批 12 T13（pi :4550 模型选择结果时机对位）。
        if (done instanceof ModelSelectorScreen) {
            maybeWarnSubscriptionAuth();
        }
        // B244（pi interactive-mode.ts:4986-4989 对位）：设置可能已改，
        // 关屏统一重建补全目录（pi 是 toggle 即时重建，窄差 D4 时机差；
        // 重建是纯读装配，无副作用）。
        chatScreen.setSlashCommands(loadSlashItems());
    }

    private void openOverlay(ScreenOverlay screenOverlay) {
        this.overlay = screenOverlay;
    }

    /**
     * 批 17（T4/T5）：扩展对话框的程序式关闭（abort/timeout/会话复位路径——
     * pi {@code hideExtensionSelector} 族的「清位＋编辑器回槽」在 java 的对位：
     * 编辑器从未离屏（裁决④），只需清位；apply 照常完成结局 future）。
     */
    void dismissOverlay(ScreenOverlay target) {
        if (overlay != target) {
            return;
        }
        overlay = null;
        target.apply(session, this::switchSession);
    }

    // ── 批 17（T7）：扩展 UI 宿主接线 ─────────────────────────

    /** 扩展 UI 宿主（bindExtensionUi 回填；null ⇒ 无扩展 UI 装配）。 */
    private TuiExtensionUiHost extensionUiHost;

    /**
     * 批 17（T7，docs/28 §3-T7.1）：回填扩展 UI 宿主——overlay 开合回调、
     * 外部编辑器往返、终端几何（host 先于 {@code AgentSession.create} 存在，
     * app 构造后回填＝晚绑定 holder）。
     */
    public void bindExtensionUi(TuiExtensionUiHost host) {
        this.extensionUiHost = host;
        host.bindApp(this::openOverlay, this::dismissOverlay, this::externalEditorRoundtrip,
            this::terminalCols, this::terminalRows);
        chatScreen.setTerminalWidthSource(this::terminalCols);
    }

    /**
     * 批 17（T4）：扩展编辑对话框的 ctrl+g 外部编辑器往返（pi
     * {@code handleOpenExternalEditor} :129-139 对位——tui.stop/start ⇒
     * java 的 alt 屏退出/重进；inline 臂摘/挂 overlay 同旨）。
     */
    private java.util.Optional<String> externalEditorRoundtrip(String command, String content) {
        boolean altScreen = runner != null;
        boolean inlineOverlay = !altScreen && inlineShell != null && inlineShell.overlayActive();
        if (altScreen) {
            TamboUIAdapter.leaveAlternateScreen();
        } else if (inlineOverlay) {
            try {
                inlineShell.endOverlay();
            } catch (IOException ignored) {
                // best-effort；下同
            }
        }
        try {
            var result =
                com.pijava.coding.agent.core.ExternalEditorSupport.edit(command, content);
            if (result instanceof com.pijava.coding.agent.core.ExternalEditorSupport
                    .Result.Complete(var text)) {
                return java.util.Optional.of(text);
            }
            return java.util.Optional.empty();
        } catch (Exception e) {
            chatScreen.showError("External editor failed: " + e.getMessage());
            return java.util.Optional.empty();
        } finally {
            if (altScreen) {
                TamboUIAdapter.enterAlternateScreen();
            } else if (inlineOverlay) {
                try {
                    inlineShell.beginOverlay();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    // ── 批 12 T13（docs/23 §3）：Anthropic 订阅凭证警告接线 ──────

    /** 警告 guard（现读 this.session 的 warnings——switchSession 后自然跟随）。 */
    private AnthropicExtraUsageWarning newSubscriptionWarning() {
        return new AnthropicExtraUsageWarning(
            () -> session.services().settings().uiPrefs().getWarnings());
    }

    /**
     * pi 四触发时机的统一入口（:1228 init／:4550 模型选择／:6120 切换／
     * :5352 登录）：命中则聊天区一条警告（每会话一次，guard 内部去重）。
     */
    private void maybeWarnSubscriptionAuth() {
        if (subscriptionWarning == null) {
            return;
        }
        subscriptionWarning.maybeWarn(session.harness().getModel(), credentialProbe)
            .ifPresent(chatScreen::appendSystemText);
    }

    /** 凭证探针注入（T13 测试面——不碰真实 env/凭证库）。 */
    void setCredentialProbe(java.util.function.Supplier<
            java.util.Optional<com.pijava.ai.auth.RecordedCredential>> probe) {
        credentialProbe = probe == null
            ? () -> com.pijava.ai.auth.Credentials.resolveCredential("anthropic") : probe;
    }

    /** 双击时间窗的时钟注入（测试面；生产 System::currentTimeMillis）。 */
    void setNowMillis(java.util.function.LongSupplier supplier) {
        nowMillis = supplier == null ? System::currentTimeMillis : supplier;
    }

    /** 终端行数（ResizeEvent 跟踪，初值 24——裁决⑧）。 */
    int terminalRows() {
        return lastTerminalRows;
    }

    /** 终端列数（ResizeEvent 跟踪，初值 80）。 */
    int terminalCols() {
        return lastTerminalCols;
    }

    /** The current modal overlay, or null (test hook). */
    ScreenOverlay currentOverlay() {
        return overlay;
    }

    /** Visible for testing (docs/16 ExitPathCloseTest 的替换臂驱动口)。 */
    void switchSession(AgentSession newSession) {
        // 批 17 评审回补（Important #2）：模式层扩展 UI/mode 随切换携带——pi
        // finishSessionReplacement→rebindSession（agent-session-runtime.ts:187-191）
        // 对位；/new（createReplacing 烘 noop）、/fork、/resume 全会话换都过此缝。
        newSession.extensionUI(session.extensionUI(), session.extensionMode());
        this.session = newSession;
        // 批 16（T4d）：渲染器注册表随会话切换重装。
        com.pijava.tui.component.ToolRenderers.install(newSession.toolRenderers());
        mode.switchSession(newSession);
        // 批 12 T13：警告 guard 随会话重建（pi anthropicSubscriptionWarningShown
        // 每会话一次）。
        subscriptionWarning = newSubscriptionWarning();
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

    // ── 批 15（T2d，pi :2168-2190＋:3586 对位）：/bug 建议 ──────────

    /** pi 逐字：{@code /\b(?:abort(?:ed)?|cancel(?:l?ed)?)\b/i}。 */
    private static final java.util.regex.Pattern ABORT_CANCEL_WORD =
        java.util.regex.Pattern.compile("\\b(?:abort(?:ed)?|cancel(?:l?ed)?)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * pi {@code maybeSuggestBugReport}：stopReason 非 error ⇒ 不提示；可重试错误
     * ⇒ 不提示；errorMessage 含 abort/cancel 词 ⇒ 不提示；每进程最多一次。
     * R29 安装变更警告门恒 false（批 15 裁决④）。裁决⑪：dim 系统文本（D83）。
     */
    private void maybeSuggestBugReport(com.pijava.ai.message.Message.AssistantMessage message) {
        if (bugReportHintShown) {
            return;
        }
        if (message.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
            return;
        }
        if (com.pijava.ai.utils.RetryableError.isRetryableAssistantError(message)) {
            return;
        }
        var errorMessage = message.errorMessage() == null ? "" : message.errorMessage();
        if (ABORT_CANCEL_WORD.matcher(errorMessage).find()) {
            return;
        }
        bugReportHintShown = true;
        chatScreen.appendSystemText(
            "If this looks like a pi-java bug, /bug sends a report to the developers.");
    }
}
