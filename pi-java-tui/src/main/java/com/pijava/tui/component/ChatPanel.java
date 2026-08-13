package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.elements.Column;

/**
 * Main chat panel: message list + scroll area (Phase 3 design §4.3).
 *
 * <p>Phase 3 renders the full list inside a column; rich virtualized
 * scrolling and long-session performance tuning are tracked as R7.</p>
 */
public final class ChatPanel {

    private final List<ChatMessage> messages = new ArrayList<>();

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

    /** Render the message list. */
    public Column render() {
        var children = messages.stream()
            .map(MessageBubble::of)
            .toList();
        return TamboUIAdapter.column(children).fill().addClass("ChatPanel");
    }
}
