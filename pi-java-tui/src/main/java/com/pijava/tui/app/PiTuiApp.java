package com.pijava.tui.app;

import com.pijava.agent.harness.WatchHandle;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ModelSelectorScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.screen.SessionListScreen;
import com.pijava.tui.screen.SettingsScreen;
import com.pijava.tui.screen.TreeSelectorScreen;
import com.pijava.tui.theme.PiTheme;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;

/**
 * TUI application shell (Phase 3 design §7.1).
 *
 * <p>Owns the render loop: drains cross-thread events each frame, intercepts
 * global keybindings, drives the interactive mode, and hosts modal overlays
 * (model/session/tree/settings selectors).</p>
 */
public final class PiTuiApp {

    private final InteractiveMode mode;
    private final ChatScreen chatScreen;
    private final KeybindingsManager keys;
    private final TuiEventDispatcher dispatcher;
    private AgentSession session;
    private ToolkitRunner runner;
    private WatchHandle<com.pijava.agent.harness.SessionSnapshot> snapshotHandle;
    private ScreenOverlay overlay;
    private boolean running = true;

    public PiTuiApp(InteractiveMode mode, ChatScreen chatScreen,
                    KeybindingsManager keys, TuiEventDispatcher dispatcher) {
        this.mode = mode;
        this.chatScreen = chatScreen;
        this.keys = keys;
        this.dispatcher = dispatcher;
        this.session = mode.session();
        chatScreen.onSubmit(mode::submit);
    }

    /** Attach to a runner: register the global key handler + snapshot feed. */
    public void start(ToolkitRunner runner) {
        this.runner = runner;
        runner.eventRouter().addGlobalHandler(this::onEvent);
        snapshotHandle = session.watchSession();
        snapshotHandle.subscribe(snapshot ->
            dispatcher.dispatch(() -> chatScreen.updateSnapshot(snapshot)));
    }

    /** Submit initial CLI messages when the TUI starts. */
    public void submitInitial(Args args) {
        if (args.messages() != null && !args.messages().isEmpty()) {
            mode.submit(String.join(" ", args.messages()));
        }
    }

    /** Per-frame root widget (drains queued observer events first). */
    public Element root() {
        dispatcher.drain();
        if (overlay != null) {
            return TamboUIAdapter.column(
                chatScreen.render().fill(),
                overlay.render(),
                chatScreen.statusBar());
        }
        return TamboUIAdapter.column(
            chatScreen.render().fill(),
            chatScreen.statusBar());
    }

    public boolean isRunning() {
        return running;
    }

    private EventResult onEvent(Event event) {
        if (event instanceof KeyEvent keyEvent) {
            debugLog("KEY code=" + keyEvent.code() + " str=" + keyEvent.string());
            return onKeyEvent(keyEvent);
        }
        if (event instanceof PasteEvent pasteEvent) {
            chatScreen.insertText(pasteEvent.text());
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private static void debugLog(String msg) {
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(
                    System.getProperty("user.home"), "tui-debug.log"),
                msg + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // best-effort diagnostic logging
        }
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
        if (TamboUIAdapter.isPlainEnter(event)) {
            submit();
            return EventResult.HANDLED;
        }
        if (TamboUIAdapter.isShiftEnter(event)) {
            chatScreen.insertNewline();
            return EventResult.HANDLED;
        }
        var keyId = keys.resolve(TamboUIAdapter.toStroke(event));
        if (keyId != null) {
            debugLog("  -> action " + keyId);
            handleAction(keyId);
            return EventResult.HANDLED;
        }
        chatScreen.onKeyEvent(event);
        debugLog("  editorText=" + chatScreen.inputText());
        return EventResult.HANDLED;
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
        if (runner != null) {
            runner.quit();
        }
    }

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
        var app = new PiTuiApp(mode, chatScreen,
            new KeybindingsManager(), dispatcher);

        // Virtual-thread events → render-thread queue (thread model §11.1).
        mode.setObservers(
            entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
            event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));

        try {
            // TamboUI auto-generates ids and auto-focuses the TextArea at first
            // render, which would swallow every key (Enter becomes a newline)
            // before our global handler runs. FocusReset keeps the app as the
            // sole owner of all keys.
            var focusReset = new FocusReset();
            var runner = TamboUIAdapter.createRunner(focusReset);
            focusReset.bind(runner.focusManager());
            runner.styleEngine(PiTheme.engineFor(themeFrom(args, session)));
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

    private static String themeFrom(Args args, AgentSession session) {
        if (args.themes() != null && !args.themes().isEmpty()) {
            return args.themes().get(0);
        }
        var theme = session.services().settings().effective().theme;
        return theme == null ? "dark" : theme;
    }

}
