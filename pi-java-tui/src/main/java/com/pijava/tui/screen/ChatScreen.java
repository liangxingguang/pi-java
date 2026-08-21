package com.pijava.tui.screen;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.EntryObserver;
import com.pijava.coding.agent.core.StreamObserver;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.component.SlashCompleter;
import com.pijava.tui.component.StatusBar;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.tui.event.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main chat screen: transcript viewport (streaming draft inside the viewport)
 * + editor, plus the status bar (Phase 3 design §7.1, alignment design §5.3).
 * Implements the coding-agent observers; all mutations happen on the render
 * thread via {@code TuiEventDispatcher}.
 */
public final class ChatScreen implements EntryObserver, StreamObserver {

    private final ChatPanel chatPanel = new ChatPanel();
    private final EditorComponent editor = new EditorComponent();
    private SlashCompleter completer = new SlashCompleter(List.of());
    private SessionSnapshot snapshot;
    private final StringBuilder assistantDraft = new StringBuilder();
    private final StringBuilder thinkingDraft = new StringBuilder();
    private String lastError;
    // Text of the user message optimistically shown on submit; matched against
    // the transcript entry when the run completes so it isn't duplicated.
    private String pendingUserText;
    // Whether the current run's assistant text was already rendered through
    // the streaming path (TextEnd). Transcript entries must not render it a
    // second time — the transcript snapshot may contain extra blocks (e.g.
    // thinking) or reordered deltas that would otherwise duplicate the bubble.
    private boolean assistantStreamed;
    // Thinking bubbles are committed at ThinkingEnd; without a TextEnd the
    // transcript entry would otherwise render them a second time.
    private boolean thinkingRendered;
    // Tool calls observed in the current run (drives the turn separator).
    private int runToolCalls;

    /** Receive a complete transcript entry (dedupes streamed assistant text). */
    @Override
    public void onEntry(Entry entry) {
        if (entry instanceof Entry.Message message
                && "assistant".equals(message.message().role())) {
            // Skip entries already rendered via the streaming path, and empty
            // assistant entries (a failed run commits one with no blocks, which
            // would otherwise render as a blank bubble above the optimistic
            // user message).
            if (assistantStreamed || thinkingRendered
                    || message.message().content().isEmpty()) {
                return;
            }
        }
        if (entry instanceof Entry.Message message
                && "user".equals(message.message().role())
                && pendingUserText != null
                && pendingUserText.equals(joinText(message.message().content()))) {
            pendingUserText = null;
            return;
        }
        chatPanel.append(ChatMessage.from(entry));
    }

    /** Incremental stream events → draft inside the viewport (typewriter). */
    @Override
    public void onStreamEvent(StreamEvent event) {
        switch (event) {
            case StreamEvent.Start ignored -> {
                assistantStreamed = false;
                thinkingRendered = false;
            }
            case StreamEvent.TextStart ignored -> {
                assistantDraft.setLength(0);
                chatPanel.setDraft(null);
            }
            case StreamEvent.TextDelta(var contentIndex, var delta, var partial) -> {
                assistantDraft.append(delta);
                chatPanel.setDraft(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent(assistantDraft.toString()))));
            }
            case StreamEvent.TextEnd(var contentIndex, var text, var partial) -> {
                chatPanel.append(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent(text))));
                assistantDraft.setLength(0);
                chatPanel.setDraft(null);
                assistantStreamed = true;
            }
            case StreamEvent.ThinkingStart ignored -> thinkingDraft.setLength(0);
            case StreamEvent.ThinkingDelta(var contentIndex, var delta, var partial) -> {
                thinkingDraft.append(delta);
                chatPanel.setDraft(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent("\uD83E\uDDD0 " + thinkingDraft))));
            }
            case StreamEvent.ThinkingEnd(var contentIndex, var thinking, var partial) -> {
                chatPanel.append(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent("\uD83E\uDDD0 " + thinking))));
                thinkingDraft.setLength(0);
                chatPanel.setDraft(null);
                thinkingRendered = true;
            }
            case StreamEvent.ToolCallStart ignored -> runToolCalls++;
            case StreamEvent.ToolCallDelta ignored -> { }
            case StreamEvent.ToolCallEnd ignored -> { }
            case StreamEvent.UsageInfo ignored -> { }
            case StreamEvent.StreamDone ignored -> { }
            case StreamEvent.StreamError(var reason, var error, var partial) -> {
                lastError = reason + (error != null ? ": " + error.getMessage() : "");
                chatPanel.append(new ChatMessage.Error(lastError));
                chatPanel.setDraft(null);
            }
        }
    }


    /**
     * Appends the startup card (Codex-CLI style: version, model, directory,
     * tips) as the first message of the transcript.
     */
    public void showWelcome(String cardText) {
        chatPanel.append(new ChatMessage.System(cardText, MetaKind.GENERIC));
    }

    /** Reset per-run tool accounting (called before each prompt submission). */
    public void resetRunTracking() {
        runToolCalls = 0;
    }

    /**
     * Appends the inter-turn separator (Codex-CLI style) once the transcript
     * entries have been committed, but only for runs that used tools.
     * Elapsed time is measured from submission to status completion.
     */
    public void finishRun(long elapsedNanos) {
        if (runToolCalls <= 0) {
            return;
        }
        long seconds = Math.max(0, elapsedNanos / 1_000_000_000L);
        String worked = seconds >= 60
            ? "Worked for " + (seconds / 60) + "m " + (seconds % 60) + "s"
            : "Worked for " + seconds + "s";
        String label = worked + " • Local tools: " + runToolCalls
            + (runToolCalls == 1 ? " call" : " calls");
        chatPanel.append(new ChatMessage.TurnSeparator(label));
    }
    /** Refresh the status bar from a session snapshot. */
    public void updateSnapshot(SessionSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Render the main chat area: transcript viewport, one blank row, then the
     * input row. There is no standalone draft bubble or separator — the draft
     * lives inside the viewport (Codex-CLI style).
     */
    public Column render() {
        var children = new ArrayList<Element>();
        children.add(chatPanel.render().fill());
        children.add(TamboUIAdapter.spacer(1));
        Element popup = completer.render();
        if (popup != null) {
            children.add(popup);
        }
        children.add(editor.render());
        return TamboUIAdapter.column(children);
    }

    /** Committed transcript snapshot for the scrollback printer. */
    public List<ChatMessage> transcriptMessages() {
        return chatPanel.messages();
    }

    /** The in-flight streaming draft for the scrollback printer. */
    public ChatMessage transcriptDraft() {
        return chatPanel.draft();
    }

    /** Editor row count (bottom-region height driver). */
    public int editorLineCount() {
        return editor.lineCount();
    }

    /**
     * Bottom region for regular (raw-scrollback) mode: editor + status bar.
     * The transcript lives in the terminal scrollback, so only this fixed
     * region is redrawn in place.
     */
    public Element renderBottomArea() {
        var children = new ArrayList<Element>();
        Element popup = completer.render();
        if (popup != null) {
            children.add(popup);
        }
        children.add(editor.render());
        children.add(statusBar());
        return TamboUIAdapter.column(children);
    }

    /** Bottom status bar (error first, then snapshot or an empty row). */
    public Element statusBar() {
        if (lastError != null) {
            return TamboUIAdapter.text("[red]" + lastError + "[/]").red().length(1);
        }
        return snapshot == null
            ? TamboUIAdapter.row().length(1)
            : new StatusBar().render(snapshot);
    }

    /** Forward a key event: slash completion first, then the editor. */
    public void onKeyEvent(KeyEvent event) {
        var action = completer.onKeyEvent(event);
        switch (action) {
            case COMPLETE -> {
                if (applyCompletion()) {
                    completer.update(editor.getText());
                }
            }
            case HANDLED -> { }
            case IGNORED -> {
                editor.onKeyEvent(event);
                completer.update(editor.getText());
            }
        }
    }

    /** Inject the slash-command catalog for completion (from the registry). */
    public void setSlashCommands(List<SlashCompleter.CommandItem> items) {
        completer = new SlashCompleter(items);
        completer.update(editor.getText());
    }

    /** Replace the input with the highlighted command (Tab or Enter). */
    public boolean applyCompletion() {
        String name = completer.selectedName();
        if (name == null) {
            return false;
        }
        editor.replaceText(name);
        completer.update(name);
        return true;
    }

    /** Whether the slash-command popup is visible. */
    public boolean completerActive() {
        return completer.active();
    }

    /** Popup row count (inline bottom-region height driver). */
    public int completerLineCount() {
        return completer.lineCount();
    }

    // ── Viewport navigation (driven by PiTuiApp) ─────────────

    /** Scrolls the transcript by {@code delta} rows (negative = up). */
    public void scrollByRows(int delta) {
        chatPanel.scrollByRows(delta);
    }

    /** Jumps to the top of the transcript. */
    public void scrollToTop() {
        chatPanel.scrollToTop();
    }

    /** Jumps to the bottom of the transcript and resumes follow. */
    public void scrollToBottom() {
        chatPanel.scrollToBottom();
    }

    /** The current first visible row (test hook). */
    public int scrollOffset() {
        return chatPanel.scrollOffset();
    }

    /** The current viewport height in rows (page-scroll driver). */
    public int visibleRows() {
        return chatPanel.visibleRows();
    }

    // ── Input portal API (PiTuiApp only touches ChatScreen) ──

    /** Clears the editor and closes the slash completer. */
    public void clearInput() {
        editor.clear();
        completer.update("");
    }

    public boolean isInputEmpty() {
        return editor.getText().isEmpty();
    }

    /** The current editor text. */
    public String inputText() {
        return editor.getText();
    }

    /** Submit the current editor content through its handler. */
    public void submitInput() {
        editor.submit();
    }

    /** Register the plain-Enter submit callback (agent prompt submission). */
    public void onSubmit(Consumer<String> handler) {
        editor.onSubmit(handler);
    }

    /** Insert a newline (Shift+Enter). */
    public void insertNewline() {
        editor.insertNewline();
    }

    /** Insert pasted text into the editor. */
    public void insertText(String text) {
        editor.insertText(text);
        completer.update(editor.getText());
    }

    /** Append a system/info bubble (slash command results). */
    public void appendSystemText(String text) {
        chatPanel.append(new ChatMessage.System(text, MetaKind.GENERIC));
    }

    /** Show the user's message immediately on submit (optimistic bubble). */
    public void appendUserText(String text) {
        pendingUserText = text;
        chatPanel.append(new ChatMessage.User(text));
    }

    /** The current snapshot (for tree selectors). */
    public SessionSnapshot snapshot() {
        return snapshot;
    }

    /** The last committed message (test hook). */
    public ChatMessage lastMessage() {
        return chatPanel.last();
    }

    /** All committed messages (test hook). */
    public java.util.List<ChatMessage> messages() {
        return chatPanel.messages();
    }

    /** The committed message count (test hook). */
    public int messageCount() {
        return chatPanel.size();
    }

    private static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent(String text1)) {
                builder.append(text1);
            }
        }
        return builder.toString();
    }

}
