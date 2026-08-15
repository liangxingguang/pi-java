package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Main chat panel: a row-level scrollable transcript viewport (Phase 3
 * alignment design §5.2), replacing the TamboUI {@code ListElement} chat
 * container.
 *
 * <p>Backed by a persistent {@link ChatViewportElement} held as a field:
 * the element owns its scroll state, so rebuilding the item list every frame
 * (messages/draft) never discards the scroll position. The streaming draft is
 * rendered as the last message inside the viewport instead of a separate
 * bubble below the list.</p>
 */
public final class ChatPanel {

    private final List<ChatMessage> messages = new ArrayList<>();
    private ChatMessage draft;
    private final ChatViewportElement element = new ChatViewportElement()
        .scrollbar(true)
        .id("chat")
        .fill()
        .addClass("ChatPanel");

    /** Append a message (thread-safe: called via the render-thread dispatcher). */
    public void append(ChatMessage message) {
        messages.add(message);
    }

    /**
     * Sets the in-flight streaming draft (null = none). The draft joins the
     * viewport as the last message; committed messages follow on TextEnd.
     */
    public void setDraft(ChatMessage message) {
        this.draft = message;
    }

    /** Clear all messages and the draft ({@code /clear} etc.). */
    public void clear() {
        messages.clear();
        draft = null;
    }

    /** The number of committed messages (for tests). */
    public int size() {
        return messages.size();
    }

    /** Snapshot of committed messages (scrollback printer). */
    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    /** The in-flight streaming draft, or null. */
    public ChatMessage draft() {
        return draft;
    }

    /** The last committed message, if any. */
    public ChatMessage last() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /** Scrolls the viewport by {@code delta} rows (negative = up). */
    public void scrollByRows(int delta) {
        element.scrollState().scrollByRows(delta, element.rowCount(), element.visibleRows());
    }

    /** Jumps to the top of the transcript. */
    public void scrollToTop() {
        element.scrollState().scrollToTop();
    }

    /** Jumps to the bottom of the transcript and resumes follow. */
    public void scrollToBottom() {
        element.scrollState().scrollToBottom();
    }

    /** The current first visible row (test hook). */
    public int scrollOffset() {
        return element.scrollState().offset();
    }

    /** The current viewport height in rows (test hook). */
    public int visibleRows() {
        return element.visibleRows();
    }

    /** Render the scrollable transcript viewport. */
    public ChatViewportElement render() {
        return element.messages(messages, draft);
    }
}
