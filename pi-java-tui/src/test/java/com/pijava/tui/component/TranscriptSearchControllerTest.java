package com.pijava.tui.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.KeybindingsManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.tamboui.style.Style;

/** L7 W6（S2a/K21）：搜索会话开关/查询/环回/高亮。 */
class TranscriptSearchControllerTest {

    private static RenderRow row(String text) {
        return new RenderRow(text, Style.EMPTY);
    }

    @Test
    void togglesAndSelectsFirstMatchAfterAnchor() {
        var rows = List.of(row("alpha"), row("beta"), row("beta"));
        var scrolled = new ArrayList<Integer>();
        var controller = new TranscriptSearchController(
            () -> rows, () -> 1, scrolled::add);
        controller.toggle();
        assertThat(controller.active()).isTrue();
        controller.input().insertText("beta");
        controller.queryChanged();
        assertThat(controller.matchCount()).isEqualTo(2);
        assertThat(controller.selectedIndex()).isZero();
        assertThat(controller.currentRow()).isEqualTo(1);
        assertThat(scrolled).contains(1);
        assertThat(controller.highlights()).hasSize(2);
        assertThat(controller.highlights().stream().filter(h -> h.current()).count())
            .isEqualTo(1);
    }

    @Test
    void nextAndPreviousWrapAround() {
        var rows = List.of(row("a"), row("b"), row("a"));
        var controller = new TranscriptSearchController(() -> rows, () -> 0, r -> { });
        controller.toggle();
        controller.input().insertText("a");
        controller.queryChanged();
        assertThat(controller.matchCount()).isEqualTo(2);
        controller.next();
        assertThat(controller.selectedIndex()).isEqualTo(1);
        controller.next();
        assertThat(controller.selectedIndex()).isZero();
        controller.previous();
        assertThat(controller.selectedIndex()).isEqualTo(1);
    }

    @Test
    void barHintUsesConfiguredKeybindings(@TempDir Path dir) throws IOException {
        var controller = new TranscriptSearchController(
            () -> List.of(row("abc")), () -> 0, r -> { });
        var bar = (TranscriptSearchBar) controller.bar();
        assertThat(bar.hintText()).isEqualTo("↑ Shift+Enter · ↓ Enter · Esc close");

        Files.writeString(dir.resolve("keybindings.json"), """
            {"tui.altScreen.searchPrevious": "up", "tui.altScreen.searchNext": "down"}
            """);
        var property = FileSettingsStorage.AGENT_DIR_PROPERTY;
        var previous = System.getProperty(property);
        System.setProperty(property, dir.toString());
        try {
            controller.setKeybindings(KeybindingsManager.create());
            var customBar = (TranscriptSearchBar) controller.bar();
            assertThat(customBar.hintText()).isEqualTo("↑ Up · ↓ Down · Esc close");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void closeClearsMatchesAndHighlights() {
        var controller = new TranscriptSearchController(
            () -> List.of(row("abc")), () -> 0, r -> { });
        controller.toggle();
        controller.input().insertText("abc");
        controller.queryChanged();
        assertThat(controller.matchCount()).isEqualTo(1);
        controller.close();
        assertThat(controller.active()).isFalse();
        assertThat(controller.highlights()).isEmpty();
    }
}
