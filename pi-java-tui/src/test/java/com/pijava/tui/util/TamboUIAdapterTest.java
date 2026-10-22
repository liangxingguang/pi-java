package com.pijava.tui.util;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 fix: Enter/control-code normalization.
 */
class TamboUIAdapterTest {

    @Test
    void enterSendsAndLfOrShiftEnterInsertsNewline() {
        // Plain Enter / CR submits (Codex composer default submit=[Enter]).
        assertThat(TamboUIAdapter.isSendEnter(KeyEvent.ofKey(KeyCode.ENTER))).isTrue();
        assertThat(TamboUIAdapter.isSendEnter(KeyEvent.ofChar('\r'))).isTrue();
        // Shift/Alt/Ctrl variants never submit.
        assertThat(TamboUIAdapter.isSendEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT))).isFalse();
        assertThat(TamboUIAdapter.isSendEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT))).isFalse();
        assertThat(TamboUIAdapter.isSendEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.CTRL))).isFalse();
        assertThat(TamboUIAdapter.isSendEnter(KeyEvent.ofChar('\r', KeyModifiers.ALT))).isFalse();

        // LF (Shift+Enter/Ctrl+J fallback) and Shift/Alt+Enter insert newlines.
        assertThat(TamboUIAdapter.isNewlineEnter(KeyEvent.ofChar('\n'))).isTrue();
        assertThat(TamboUIAdapter.isNewlineEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT))).isTrue();
        assertThat(TamboUIAdapter.isNewlineEnter(
            KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT))).isTrue();
        assertThat(TamboUIAdapter.isNewlineEnter(KeyEvent.ofKey(KeyCode.ENTER))).isFalse();
        assertThat(TamboUIAdapter.isNewlineEnter(KeyEvent.ofChar('x'))).isFalse();
    }

    @Test
    void ctrlControlCodesMapToLetters() {
        assertThat(TamboUIAdapter.toStroke(
            KeyEvent.ofChar(3, KeyModifiers.CTRL)).key()).isEqualTo("c");
        assertThat(TamboUIAdapter.toStroke(
            KeyEvent.ofChar(4, KeyModifiers.CTRL)).key()).isEqualTo("d");
        assertThat(TamboUIAdapter.toStroke(
            KeyEvent.ofChar(16, KeyModifiers.CTRL)).key()).isEqualTo("p");
    }

    @Test
    void plainCharMapsToItself() {
        assertThat(TamboUIAdapter.toStroke(KeyEvent.ofChar('/')).key())
            .isEqualTo("/");
    }

    @Test
    void uppercaseCharYieldsShiftLetterStroke() {
        // 批 9 评审 I1：pi keys.ts:1222-1229 的 legacy 规则——终端不报 SHIFT
        // 修饰键，打出的**大写字母即 shift+letter**（否则 shift+l/t 类绑定
        // 在真实输入下永不可达）。
        var stroke = TamboUIAdapter.toStroke(KeyEvent.ofChar('L'));
        assertThat(stroke).isEqualTo(
            com.pijava.coding.agent.core.KeybindingsManager.KeyStroke.of(
                "l", false, false, true));
        // 小写不带 shift；ctrl/alt 同按时不脑补 shift。
        assertThat(TamboUIAdapter.toStroke(KeyEvent.ofChar('l'))).isEqualTo(
            com.pijava.coding.agent.core.KeybindingsManager.KeyStroke.of(
                "l", false, false, false));
        assertThat(TamboUIAdapter.toStroke(
            KeyEvent.ofChar('L', KeyModifiers.CTRL)).shift()).isFalse();
        // 数字/符号非字母，不受影响。
        assertThat(TamboUIAdapter.toStroke(KeyEvent.ofChar('!')).shift()).isFalse();
    }

    // ── 批 12 T9（docs/23 §3/§7）：showHardwareCursor ⇒ hideCursor 取反 ──

    /** 不建真终端后端（JLine 无控制台时阻塞）；配置断言不需要 IO。 */
    private static final dev.tamboui.terminal.Backend NOOP_BACKEND =
        new dev.tamboui.terminal.Backend() {
            @Override public void draw(dev.tamboui.buffer.DiffResult diff) { }
            @Override public void flush() { }
            @Override public void clear() { }
            @Override public dev.tamboui.layout.Size size() {
                return new dev.tamboui.layout.Size(80, 24);
            }
            @Override public void showCursor() { }
            @Override public void hideCursor() { }
            @Override public dev.tamboui.layout.Position getCursorPosition() {
                return new dev.tamboui.layout.Position(0, 0);
            }
            @Override public void setCursorPosition(dev.tamboui.layout.Position position) { }
            @Override public void enterAlternateScreen() { }
            @Override public void leaveAlternateScreen() { }
            @Override public void enableRawMode() { }
            @Override public void disableRawMode() { }
            @Override public void onResize(Runnable handler) { }
            @Override public int read(int timeout) {
                return -1;
            }
            @Override public int peek(int timeout) {
                return -1;
            }
            @Override public void close() { }
        };

    @Test
    void hardwareCursorOnLeavesTheTerminalCursorVisible() {
        // pi tui.ts:1477-1484 对位：开＝真终端光标（hideCursor(false)）。
        assertThat(TamboUIAdapter.tuiConfig(NOOP_BACKEND, true).hideCursor()).isFalse();
    }

    @Test
    void hardwareCursorOffKeepsTheFakeCursor() {
        // 默认关＝自绘假光标（hideCursor(true)）——pi/java 默认皆关。
        assertThat(TamboUIAdapter.tuiConfig(NOOP_BACKEND, false).hideCursor()).isTrue();
    }
}
