package com.pijava.tui.app;

import java.io.IOException;
import java.util.List;

import com.pijava.agent.harness.WatchHandle;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.SlashCompleter;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ModelSelectorScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.screen.SessionListScreen;
import com.pijava.tui.screen.SettingsScreen;
import com.pijava.tui.screen.TreeSelectorScreen;
import com.pijava.tui.screen.WelcomeOverlay;
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
    private AgentSession session;
    private ToolkitRunner runner;
    private WatchHandle<com.pijava.agent.harness.SessionSnapshot> snapshotHandle;
    // Written on the render thread (dispatcher drain) and read by the global
    // key handler plus test hooks on other threads — needs volatile visibility.
    private volatile ScreenOverlay overlay;
    private volatile boolean running = true;
    private InlineTuiShell inlineShell;
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
        chatScreen.setSlashCommands(loadSlashItems());
        chatScreen.onSubmit(this::submitPrompt);
    }

    /** Build the completion catalog from the slash-command registry. */
    private List<SlashCompleter.CommandItem> loadSlashItems() {
        var registry = session.services().slashCommands();
        return registry.names().stream()
            .map(name -> {
                var command = registry.get(name);
                return new SlashCompleter.CommandItem(
                    name, command.argumentHint(), command.description());
            })
            .toList();
    }

    /** Attach to a runner: register the global key handler + snapshot feed. */
    public void start(ToolkitRunner runner) {
        showWelcomeOnce();
        this.runner = runner;
        runner.eventRouter().addGlobalHandler(this::onEvent);
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
        snapshotHandle = session.watchSession();
        snapshotHandle.subscribe(snapshot ->
            dispatcher.dispatch(() -> chatScreen.updateSnapshot(snapshot)));
        // Regular mode redraws on demand: async dispatches mark the shell
        // dirty, so the idle loop never repaints and the terminal scrollback
        // stays exactly where the user scrolled it.
        dispatcher.setWake(shell::markDirty);
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
                overlay.apply(session, this::switchSession);
                overlay = null;
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
            case CommandRegistry.UI_TREE_SELECTOR -> {
                var snapshot = chatScreen.snapshot();
                if (snapshot != null) {
                    openOverlay(new TreeSelectorScreen(snapshot));
                } else {
                    chatScreen.appendSystemText("No session snapshot yet.");
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

    private void handleAction(String keyId) {
        switch (keyId) {
            case KeybindingsManager.INTERRUPT -> mode.abort();
            case KeybindingsManager.FOLLOW_UP -> {
                if (!chatScreen.isInputEmpty()) {
                    mode.followUp(chatScreen.inputText());
                    chatScreen.clearInput();
                }
            }
            case KeybindingsManager.CLEAR -> chatScreen.clearInput();
            case KeybindingsManager.EXIT -> {
                if (chatScreen.isInputEmpty()) {
                    exit();
                }
            }
            case KeybindingsManager.MODEL_SELECT ->
                openOverlay(new ModelSelectorScreen(session));
            default -> { /* model.cycle / thinking.* / tools.expand / editor.external / dequeue → Phase 6 */ }
        }
    }

    private void openOverlay(ScreenOverlay screenOverlay) {
        this.overlay = screenOverlay;
    }

    /** The current modal overlay, or null (test hook). */
    ScreenOverlay currentOverlay() {
        return overlay;
    }

    private void switchSession(AgentSession newSession) {
        this.session = newSession;
        mode.switchSession(newSession);
        if (snapshotHandle != null) {
            snapshotHandle.close();
        }
        snapshotHandle = newSession.watchSession();
        snapshotHandle.subscribe(snapshot ->
            dispatcher.dispatch(() -> chatScreen.updateSnapshot(snapshot)));
    }

    private void exit() {
        running = false;
        if (inlineShell != null) {
            inlineShell.quit();
        }
        if (runner != null) {
            runner.quit();
        }
    }

}
