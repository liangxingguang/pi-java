package com.pijava.tui.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.harness.WatchHandle;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;
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
        this.sessionEvents = new SessionEventChannel(dispatcher, chatScreen::onSessionEvent);
        chatScreen.setSlashCommands(loadSlashItems());
        chatScreen.setInterruptHint(
            KeybindingHints.keyText(keys.strokeFor(KeybindingsManager.INTERRUPT)));
        // docs/21（§3.7）：排队区 hint 行键名随绑定表取（pi getAppKeyDisplay 对位）。
        chatScreen.setDequeueHint(
            KeybindingHints.displayText(keys.strokeFor(KeybindingsManager.DEQUEUE)));
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
        runner.eventRouter().addGlobalHandler(this::onEvent);
        sessionEvents.open(session, null);
        snapshotHandle = session.watchSession();
        snapshotHandle.subscribe(snapshot ->
            dispatcher.dispatch(() -> chatScreen.updateSnapshot(snapshot)));
    }

    /** Attach the raw-scrollback inline shell (regular TUI mode). */
    public void startInline(InlineTuiShell shell) {
        showWelcomeOnce();
        this.inlineShell = shell;
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
        inlineShell.setContentHeight(
            chatScreen.completerLineCount() + editorLines + 1);
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
            if (overlay.isDone()) {
                // docs/18：先清位再 apply——apply 可链式开下一个 overlay
                // （树导航流：树选择器→三选→编辑器），旧序会把它立即抹掉。
                var done = overlay;
                overlay = null;
                done.apply(session, this::switchSession);
                // B244（pi interactive-mode.ts:4986-4989 对位）：设置可能已改，
                // 关屏统一重建补全目录（pi 是 toggle 即时重建，窄差 D4 时机差；
                // 重建是纯读装配，无副作用）。
                chatScreen.setSlashCommands(loadSlashItems());
            }
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
            command.thenAccept(result ->
                dispatcher.dispatch(() -> handleCommandResult(result)));
        } else {
            chatScreen.appendUserText(text);
            chatScreen.submitInput();
        }
    }

    private void handleCommandResult(String result) {
        if (result == null) {
            return;
        }
        switch (result) {
            case CommandRegistry.UI_MODEL_SELECTOR ->
                openOverlay(new ModelSelectorScreen(session));
            case CommandRegistry.UI_SESSION_SELECTOR ->
                openOverlay(new SessionListScreen(session.listSessions()));
            case CommandRegistry.UI_TREE_SELECTOR ->
                // docs/18（§3.8/§3.9）＋ docs/19（§3.9）：/tree 走原地导航流
                // （entry 树选择器 → 选 entry 后三选摘要）。
                new TreeNavigationFlow(session, mode, chatScreen, dispatcher,
                    this::openOverlay, this::terminalRows, this::terminalCols, keys)
                    .begin();
            case CommandRegistry.UI_FORK_SELECTOR -> {
                // docs/21（T3，B254）：/fork＝user-message 选择器（pi
                // showUserMessageSelector，interactive-mode.ts:5500-5538 对位）。
                var forkMessages = session.getUserMessagesForForking();
                if (forkMessages.isEmpty()) {                       // pi :5503-5506
                    chatScreen.appendSystemText("No messages to fork from");
                } else {
                    var initialSelectedId =
                        forkMessages.get(forkMessages.size() - 1).entryId();
                    openOverlay(new UserMessageSelectorScreen(forkMessages, initialSelectedId,
                        this::terminalCols,
                        entryId -> forkFromMessage(entryId, forkMessages),
                        () -> { }));                                // Esc：pi 的 onCancel 只关选择器
                }
            }
            case CommandRegistry.UI_SETTINGS ->
                openOverlay(new SettingsScreen(session));
            case CommandRegistry.UI_SCOPED_MODELS ->
                chatScreen.appendSystemText(
                    "Use /scoped-models +<model> or /scoped-models -<model>");
            default -> chatScreen.appendSystemText(result);
        }
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
                }
                mode.abort();
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
            case KeybindingsManager.MODEL_CYCLE -> session.cycleModelForward();
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
            default -> { /* thinking.* / tools.expand / editor.external → Phase 6 */ }
        }
    }

    private void openOverlay(ScreenOverlay screenOverlay) {
        this.overlay = screenOverlay;
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
        this.session = newSession;
        mode.switchSession(newSession);
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
}
