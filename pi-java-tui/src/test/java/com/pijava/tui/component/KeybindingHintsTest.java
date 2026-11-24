package com.pijava.tui.component;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 键位提示文本：{@code app.interrupt} 的默认绑定必须展开成 pi 显示的那个
 * {@code esc}（pi {@code keyText("app.interrupt")}，{@code keybinding-hints.ts:37-44}）。
 */
class KeybindingHintsTest {

    @Test
    void theDefaultInterruptStrokeRendersAsPiShowsIt() {
        var keys = new KeybindingsManager();

        assertThat(KeybindingHints.keyText(keys.strokeFor(KeybindingsManager.INTERRUPT)))
            .isEqualTo("esc");
    }

    @Test
    void modifiersAreJoinedWithPlusInCtrlAltShiftOrder() {
        assertThat(KeybindingHints.keyText(KeyStroke.of("p", true, false, false)))
            .isEqualTo("ctrl+p");
        assertThat(KeybindingHints.keyText(KeyStroke.of("p", true, true, true)))
            .isEqualTo("ctrl+alt+shift+p");
        assertThat(KeybindingHints.keyText(KeyStroke.of("tab", false, false, true)))
            .isEqualTo("shift+tab");
    }

    @Test
    void missingStrokesRenderAsEmpty() {
        assertThat(KeybindingHints.keyText(null)).isEmpty();
        assertThat(KeybindingHints.keyText(KeyStroke.of("", false, false, false))).isEmpty();
    }

    @Test
    void formatsRawKeyTextWithAlternativesAndCapitals() {
        assertThat(KeybindingHints.formatKeyText("ctrl+shift+f", false))
            .isEqualTo("ctrl+shift+f");
        assertThat(KeybindingHints.formatKeyText("enter/ctrl+g", true))
            .isEqualTo("Enter/Ctrl+G");
    }

    @Test
    void rawKeyHintCarriesKeyAndDescription() {
        var element = KeybindingHints.rawKeyHint("esc", "close");
        assertThat(element).isNotNull();
        assertThat(KeybindingHints.keyHint(KeyStroke.of("p", true, false, false), "palette"))
            .isNotNull();
    }
}
