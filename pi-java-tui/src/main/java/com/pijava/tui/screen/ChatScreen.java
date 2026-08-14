package com.pijava.tui.screen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.EntryObserver;
import com.pijava.coding.agent.core.StreamObserver;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.StatusBar;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Main chat screen: message panel + draft bubble + editor, plus the status
 * bar (Phase 3 design §7.1). Implements the coding-agent observers; all
 * mutations happen on the render thread via {@code TuiEventDispatcher}.
 */
public final class ChatScreen implements EntryObserver, StreamObserver {

    private final ChatPanel chatPanel = new ChatPanel();
    private final EditorComponent editor = new EditorComponent();
    private SessionSnapshot snapshot;
    private final StringBuilder assistantDraft = new StringBuilder();
    private final StringBuilder thinkingDraft = new StringBuilder();
    private String lastError;
    // Text streamed for the assistant message currently in flight; finalized
    // (pushed onto committedTexts) when the message ends (Start/StreamDone).
    private final StringBuilder currentMessageText = new StringBuilder();
    // Fully streamed assistant-message texts, in transcript order, waiting to
    // be matched (and consumed) by the batched onEntry calls.
    private final Deque<String> committedTexts = new ArrayDeque<>();

    /** Receive a complete transcript entry (dedupes streamed assistant text). */
    @Override
    public void onEntry(Entry entry) {
        if (entry instanceof Entry.Message message
                && "assistant".equals(message.role())) {
            // Skip an assistant entry whose text was already committed by the
            // stream (TextEnd); otherwise append it (e.g. non-streaming paths).
            if (!committedTexts.isEmpty()
                    && committedTexts.peek().equals(joinText(message.blocks()))) {
                committedTexts.poll();
                return;
            }
        }
        chatPanel.append(ChatMessage.from(entry));
    }

    /** Incremental stream events → draft bubble (typewriter effect). */
    @Override
    public void onStreamEvent(StreamEvent event) {
        switch (event) {
            case StreamEvent.Start ignored -> finalizeMessage();
            case StreamEvent.TextStart ignored -> assistantDraft.setLength(0);
            case StreamEvent.TextDelta(var contentIndex, var delta, var partial) ->
                assistantDraft.append(delta);
            case StreamEvent.TextEnd(var contentIndex, var text, var partial) -> {
                chatPanel.append(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent(text))));
                currentMessageText.append(text);
                assistantDraft.setLength(0);
            }
            case StreamEvent.ThinkingStart ignored -> thinkingDraft.setLength(0);
            case StreamEvent.ThinkingDelta(var contentIndex, var delta, var partial) ->
                thinkingDraft.append(delta);
            case StreamEvent.ThinkingEnd(var contentIndex, var thinking, var partial) -> {
                chatPanel.append(new ChatMessage.Assistant(
                    List.of(new ContentBlock.TextContent("\uD83E\uDDD0 " + thinking))));
                thinkingDraft.setLength(0);
            }
            case StreamEvent.ToolCallStart ignored -> { }
            case StreamEvent.ToolCallDelta ignored -> { }
            case StreamEvent.ToolCallEnd ignored -> { }
            case StreamEvent.UsageInfo ignored -> { }
            case StreamEvent.StreamDone ignored -> finalizeMessage();
            case StreamEvent.StreamError(var reason, var error, var partial) -> {
                lastError = reason + (error != null ? ": " + error.getMessage() : "");
                currentMessageText.setLength(0);
                chatPanel.append(new ChatMessage.Error(lastError));
            }
        }
    }

    /** Refresh the status bar from a session snapshot. */
    public void updateSnapshot(SessionSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** In-flight draft bubble (assistant/thinking), empty row when idle. */
    private Element draftBubble() {
        var text = assistantDraft.length() > 0 ? assistantDraft.toString()
            : thinkingDraft.length() > 0 ? "\uD83E\uDDD0 " + thinkingDraft : null;
        return text == null
            ? TamboUIAdapter.row().fill()
            : TamboUIAdapter.panel(TamboUIAdapter.markupText(text))
                .cyan().rounded();
    }

    /** Render the main chat area. */
    public Column render() {
        return TamboUIAdapter.column(
            chatPanel.render().fill(),
            draftBubble(),
            editor.render());
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

    /** Forward a key event to the editor (single input portal). */
    public void onKeyEvent(KeyEvent event) {
        editor.onKeyEvent(event);
    }

    // ── Input portal API (PiTuiApp only touches ChatScreen) ──

    public void clearInput() {
        editor.clear();
    }

    public boolean isInputEmpty() {
        return editor.getText().isEmpty();
    }

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
    }

    /** Append a system/info bubble (slash command results). */
    public void appendSystemText(String text) {
        chatPanel.append(new ChatMessage.System(text));
    }

    /** The current snapshot (for tree selectors). */
    public SessionSnapshot snapshot() {
        return snapshot;
    }

    /** The last committed message (test hook). */
    public ChatMessage lastMessage() {
        return chatPanel.last();
    }

    /** The committed message count (test hook). */
    public int messageCount() {
        return chatPanel.size();
    }

    private static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }

    /** Close the in-flight streamed message: record its text for onEntry dedup. */
    private void finalizeMessage() {
        if (!currentMessageText.isEmpty()) {
            committedTexts.add(currentMessageText.toString());
        }
        currentMessageText.setLength(0);
    }
}
