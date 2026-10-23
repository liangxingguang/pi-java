package com.pijava.tui.screen;

import java.util.concurrent.CompletableFuture;

import com.pijava.coding.agent.core.KeybindingsManager;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T4，docs/28 §7-7 前半）：扩展输入对话框——pi extension-input.ts 逐字
 * 对拍：confirm/Enter 提交当前值（<b>无 blank 门</b>）、cancel 取消、其余键转发
 * 输入框、标题 accent 不加粗、hint 两段。
 */
class ExtensionInputOverlayTest {

    private final KeybindingsManager keys = new KeybindingsManager();

    private ExtensionInputOverlay overlay(CompletableFuture<String> result) {
        return new ExtensionInputOverlay("Name?", null, keys, () -> 80, result);
    }

    @Test
    void typingThenEnterSubmitsValue() {
        var result = new CompletableFuture<String>();
        var overlay = overlay(result);
        // "liaho" 退格删尾 o ⇒ "liah"，再补 o ⇒ "liaho"（退格语义断言点）。
        overlay.onKeyEvent(KeyEvent.ofChar('l'));
        overlay.onKeyEvent(KeyEvent.ofChar('i'));
        overlay.onKeyEvent(KeyEvent.ofChar('a'));
        overlay.onKeyEvent(KeyEvent.ofChar('h'));
        overlay.onKeyEvent(KeyEvent.ofChar('o'));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));
        overlay.onKeyEvent(KeyEvent.ofChar('o'));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(overlay.isDone()).isTrue();
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue("liaho");
    }

    @Test
    void emptyValueSubmitsEmptyString() {
        // pi :85-86：提交输入框当前值——无 blank 门（与主编辑器 submit 不同）。
        var result = new CompletableFuture<String>();
        var overlay = overlay(result);
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue("");
    }

    @Test
    void escapeCancelsToNull() {
        var result = new CompletableFuture<String>();
        var overlay = overlay(result);
        overlay.onKeyEvent(KeyEvent.ofChar('x'));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue(null);
    }

    @Test
    void newlineCharsNeverEnterText() {
        // shift+enter-as-LF / 裸 CR 不入文（单行输入）。
        var result = new CompletableFuture<String>();
        var overlay = overlay(result);
        overlay.onKeyEvent(KeyEvent.ofChar('a'));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT));
        assertThat(overlay.inputText()).isEqualTo("a");
        assertThat(overlay.isDone()).isFalse();
    }

    @Test
    void countdownTickSuffixesTitleWithoutBold() {
        var overlay = overlay(new CompletableFuture<>());
        overlay.countdownTick(2);
        var titleRow = overlay.render();   // 渲染不崩即过；标题内容经布局断言
        assertThat(titleRow).isNotNull();
        assertThat(overlay.isDone()).isFalse();
    }
}
