package com.pijava.tui.component;

import java.util.List;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slash-command completion: activation on "/", prefix filtering, selection
 * navigation, Tab completion and Esc dismissal.
 */
class SlashCompleterTest {

    private static final List<SlashCompleter.CommandItem> ITEMS = List.of(
        new SlashCompleter.CommandItem("model", "<id>", "Switch model"),
        new SlashCompleter.CommandItem("models", "", "List models"),
        new SlashCompleter.CommandItem("help", "", "Show help"),
        new SlashCompleter.CommandItem("settings", "", "Open settings"));

    private static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code, KeyModifiers.of(false, false, false));
    }

    @Test
    void activatesOnSlashAndFiltersByPrefix() {
        var completer = new SlashCompleter(ITEMS);
        completer.update("/");
        assertThat(completer.active()).isTrue();
        assertThat(completer.matches()).hasSize(4);

        completer.update("/mo");
        assertThat(completer.active()).isTrue();
        assertThat(completer.matches())
            .extracting(SlashCompleter.CommandItem::name)
            .containsExactly("model", "models");

        completer.update("/x");
        assertThat(completer.active()).isFalse();
    }

    @Test
    void closesOnSpaceNewlineOrPlainText() {
        var completer = new SlashCompleter(ITEMS);
        completer.update("/mo x");
        assertThat(completer.active()).isFalse();
        completer.update("/mo\n");
        assertThat(completer.active()).isFalse();
        completer.update("hello");
        assertThat(completer.active()).isFalse();
    }

    @Test
    void arrowsMoveSelectionAndTabCompletes() {
        var completer = new SlashCompleter(ITEMS);
        completer.update("/");
        assertThat(completer.selectedName()).isEqualTo("/model");

        assertThat(completer.onKeyEvent(key(KeyCode.DOWN)))
            .isEqualTo(SlashCompleter.KeyAction.HANDLED);
        assertThat(completer.selectedName()).isEqualTo("/models");

        assertThat(completer.onKeyEvent(key(KeyCode.TAB)))
            .isEqualTo(SlashCompleter.KeyAction.COMPLETE);
    }

    @Test
    void escapeClosesThePanel() {
        var completer = new SlashCompleter(ITEMS);
        completer.update("/");
        assertThat(completer.onKeyEvent(key(KeyCode.ESCAPE)))
            .isEqualTo(SlashCompleter.KeyAction.HANDLED);
        assertThat(completer.active()).isFalse();
    }
}
