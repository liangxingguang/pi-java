package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;

/**
 * Main chat panel: a scrollable message list (Phase 3 design §4.3).
 *
 * <p>Backed by {@link ListElement} with sticky auto-scroll: new messages keep
 * the viewport pinned to the bottom, and PageUp/PageDown or the scroll wheel
 * browse history without losing the latest content.</p>
 */
public final class ChatPanel {

    private final List<ChatMessage> messages = new ArrayList<>();
    // Scrollable list with sticky auto-scroll: new messages stay visible,
    // PageUp/PageDown (or scroll wheel) browse history.
    private final ListElement<ChatMessage> element = new ListElement<ChatMessage>()
        .stickyScroll()
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
