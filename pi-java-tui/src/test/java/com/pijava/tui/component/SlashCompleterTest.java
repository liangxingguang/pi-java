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

    // ── 批 12 T5（docs/23 §3/§7）：autocompleteMaxVisible（裁决④ 默认 5）──

    private static List<SlashCompleter.CommandItem> manyItems(int count) {
        var items = new java.util.ArrayList<SlashCompleter.CommandItem>();
        for (int i = 0; i < count; i++) {
            items.add(new SlashCompleter.CommandItem("cmd" + i, "", "item " + i));
        }
        return List.copyOf(items);
    }

    @Test
    void defaultMaxVisibleIsFive() {
        var completer = new SlashCompleter(manyItems(9));
        completer.update("/");

        assertThat(completer.lineCount()).isEqualTo(6);   // 5 行＋「… N more」
    }

    @Test
    void maxVisibleClampsRowsAndCountsTheRemainder() throws Exception {
        var completer = new SlashCompleter(manyItems(9), () -> 3);
        completer.update("/");

        assertThat(completer.lineCount()).isEqualTo(4);   // 3 行＋汇总行
        assertThat(renderLines(completer.render(), 40, 4).get(3)).contains("… 6 more");
        // live-read：改值下一帧生效。
        completer = new SlashCompleter(manyItems(9), () -> 20);
        completer.update("/");
        assertThat(completer.lineCount()).isEqualTo(9);
    }

    private static List<String> renderLines(dev.tamboui.toolkit.element.Element element,
            int width, int height) throws Exception {
        var buffer = dev.tamboui.buffer.Buffer.empty(new dev.tamboui.layout.Rect(0, 0, width, height));
        var frame = dev.tamboui.terminal.Frame.forTesting(buffer);
        var focus = new dev.tamboui.toolkit.focus.FocusManager();
        var mark = dev.tamboui.tui.RenderThread.class.getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
        try {
            element.render(frame, new dev.tamboui.layout.Rect(0, 0, width, height),
                new dev.tamboui.toolkit.element.DefaultRenderContext(focus,
                    new dev.tamboui.toolkit.event.EventRouter(focus,
                        new dev.tamboui.toolkit.element.ElementRegistry())));
        } finally {
            var clear = dev.tamboui.tui.RenderThread.class.getDeclaredMethod("clearRenderThread");
            clear.setAccessible(true);
            clear.invoke(null);
        }
        var lines = new java.util.ArrayList<String>();
        for (int y = 0; y < height; y++) {
            var row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                var cell = buffer.get(x, y);
                if (!cell.isContinuation()) {
                    row.append(cell.symbol());
                }
            }
            lines.add(row.toString().stripTrailing());
        }
        return lines;
    }
}
