package com.pijava.tui.component;

import java.util.function.Consumer;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.elements.TextAreaElement;
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

    /** Forward a key event to the editor state. */
    public void onKeyEvent(KeyEvent event) {
        element.handleKeyEvent(event, false);
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
