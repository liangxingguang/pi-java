package com.pijava.tui.component;

import java.util.function.Consumer;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.elements.TextAreaElement;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;

/**
 * Multi-line input editor delegating to the TamboUI TextArea
 * (Phase 3 design §6; syntax highlighting/autocomplete land Phase 6).
 */
public final class EditorComponent {

    private final TextAreaState state = new TextAreaState();
    private final TextAreaElement element;
    private Consumer<String> submitHandler = text -> { };

    public EditorComponent() {
        this.element = TamboUIAdapter.textArea(state)
            .placeholder("Type your message... (Enter submit, Alt+Enter queue, Esc interrupt)")
            .fill()
            .addClass("EditorComponent");
    }

    /** The editor widget for the render tree. */
    public TextAreaElement render() {
        return element;
    }

    /**
     * Handle a key event by driving the {@link TextAreaState} directly.
     *
     * <p>The app shell owns all keys (the editor element has no id, so the
     * TamboUI focus system never routes keys to it); typing and navigation
     * are applied here. Enter/Shift+Enter and app.* shortcuts are handled by
     * the app shell before this method is reached, which also avoids the
     * {@code TextAreaElement} swallowing Enter as a newline.</p>
     */
    public void onKeyEvent(KeyEvent event) {
        if (event.hasCtrl() || event.hasAlt()) {
            return; // app shortcuts / paste handled elsewhere
        }
        switch (event.code()) {
            case KeyCode.CHAR -> {
                var text = event.string();
                if (text != null && !text.isBlank()) {
                    state.insert(text);
                }
            }
            case KeyCode.BACKSPACE -> state.deleteBackward();
            case KeyCode.DELETE -> state.deleteForward();
            case KeyCode.LEFT -> state.moveCursorLeft();
            case KeyCode.RIGHT -> state.moveCursorRight();
            case KeyCode.UP -> state.moveCursorUp();
            case KeyCode.DOWN -> state.moveCursorDown();
            case KeyCode.HOME -> state.moveCursorToLineStart();
            case KeyCode.END -> state.moveCursorToLineEnd();
            case KeyCode.PAGE_UP -> state.scrollUp(1);
            case KeyCode.PAGE_DOWN -> state.scrollDown(1, state.lineCount());
            default -> { }
        }
    }

    /** Insert pasted text at the cursor (bracketed paste support). */
    public void insertText(String text) {
        if (text != null && !text.isEmpty()) {
            state.insert(text);
        }
    }

    /** Register the submit callback (invoked by the app on plain Enter). */
    public void onSubmit(Consumer<String> handler) {
        this.submitHandler = handler;
    }

    /** Current editor text. */
    public String getText() {
        return state.text();
    }

    /** Clear the editor. */
    public void clear() {
        state.clear();
    }

    /** Replace the editor content. */
    public void setText(String text) {
        state.setText(text);
    }

    /** Insert a newline (Shift+Enter). */
    public void insertNewline() {
        state.insert("\n");
    }

    /** Notify the submit handler with the current content. */
    public void submit() {
        var text = state.text();
        if (!text.isBlank()) {
            submitHandler.accept(text);
            state.clear();
        }
    }
}
