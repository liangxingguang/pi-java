package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;

/**
 * Main chat panel: a scrollable message list (Phase 3 design §4.3).
 *
 * <p>Backed by a persistent {@link ListElement} held as a field, following
 * TamboUI's official chat-pane pattern (sticky auto-scroll, no selection
 * highlight, visible scrollbar). Rebuilding the list inline every render
 * discards the scroll state, so the same element is reused and only its items
 * are refreshed each frame.</p>
 */
public final class ChatPanel {

    private final List<ChatMessage> messages = new ArrayList<>();
    // Official log/chat-style configuration: no row is selected, no highlight
    // symbol or inverted row, sticky scroll (new content stays visible until
    // the user scrolls away), a visible scrollbar, and a stable focus id.
    private final ListElement<ChatMessage> element = new ListElement<ChatMessage>()
        .selected(-1)
        .highlightSymbol("")
        .highlightStyle(dev.tamboui.style.Style.EMPTY)
        .displayOnly()
        .stickyScroll()
        .scrollbar()
        .id("chat")
        .fill()
        .addClass("ChatPanel");

    /** Append a message (thread-safe: called via the render-thread dispatcher). */
    public void append(ChatMessage message) {
        messages.add(message);
    }

    /** Clear all messages ({@code /clear} etc.). */
    public void clear() {
        messages.clear();
    }

    /** The number of messages (for tests). */
    public int size() {
        return messages.size();
    }

    /** The last message, if any. */
    public ChatMessage last() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /** Render the scrollable message list. */
    public StyledElement<?> render() {
        var items = messages.stream()
            .map(MessageBubble::of)
            .map(StyledElement.class::cast)
            .toArray(StyledElement<?>[]::new);
        element.elements(items);
        return element;
    }
}
