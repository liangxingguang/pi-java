package com.pijava.tui.screen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.pijava.coding.agent.core.KeybindingsManager;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T4，docs/28 §7-6）：扩展选择器——pi extension-selector.ts 逐字对拍：
 * 键序（up/down/k/j/enter/esc/ctrl+c）、空表不提交、hint 行文本、timeout 倒计时
 * 标题与到期取消（tick/expire 直驱——pi CountdownTimer 的两回调）。
 */
class ExtensionSelectorOverlayTest {

    private final KeybindingsManager keys = new KeybindingsManager();

    private ExtensionSelectorOverlay overlay(List<String> options,
                                             CompletableFuture<String> result) {
        return new ExtensionSelectorOverlay("Pick", options, keys, () -> 80, result);
    }

    private static String visibleText(List<List<ExtensionSelectorOverlay.Span>> layout) {
        var sb = new StringBuilder();
        for (var row : layout) {
            for (var span : row) {
                sb.append(span.text());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── 键序（pi handleInput :85-105）──

    @Test
    void arrowAndVimKeysNavigateWithClamp() {
        var result = new CompletableFuture<String>();
        var overlay = overlay(List.of("a", "b", "c"), result);

        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        overlay.onKeyEvent(KeyEvent.ofChar('j'));
        overlay.onKeyEvent(KeyEvent.ofChar('j'));   // 夹 length-1
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(overlay.isDone()).isTrue();
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue("c");

        result = new CompletableFuture<>();
        overlay = overlay(List.of("a", "b"), result);
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.UP));      // 夹 0
        overlay.onKeyEvent(KeyEvent.ofChar('k'));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue("a");
    }

    @Test
    void modifierPrefixedKIsNotNavigation() {
        // "k" 别名只吃裸键（ctrl+k 是编辑器的 kill——pi 的 keyData === "k" 逐字）。
        var result = new CompletableFuture<String>();
        var overlay = overlay(List.of("a", "b"), result);
        overlay.onKeyEvent(KeyEvent.ofChar('k', KeyModifiers.CTRL));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue("a");   // 仍在 0
    }

    @Test
    void emptyOptionsNeverSubmit() {
        var result = new CompletableFuture<String>();
        var overlay = overlay(List.of(), result);
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(result).as("空表不提交（pi :97-99）").isNotCompleted();
        assertThat(overlay.isDone()).isFalse();

        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertThat(overlay.isDone()).isTrue();
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue(null);
    }

    @Test
    void ctrlCAlsoCancels() {
        var result = new CompletableFuture<String>();
        var overlay = overlay(List.of("a"), result);
        overlay.onKeyEvent(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        assertThat(overlay.isDone()).isTrue();
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue(null);
    }

    // ── 渲染行形（pi 构造器子件序＋updateList）──

    @Test
    void layoutMatchesPiRowShape() {
        var overlay = overlay(List.of("a", "b"), new CompletableFuture<>());
        var text = visibleText(overlay.layout());
        assertThat(text).contains("─".repeat(80))       // DynamicBorder 上下
            .contains(" Pick")                          // 标题（accent 加粗，paddingX=1）
            .contains(" → a")                           // 选中项前缀
            .contains("   b")                           // 未选两空＋padding
            .contains("↑↓ navigate")                    // hint 行逐字
            .contains("enter select")
            .contains("esc cancel");
    }

    // ── 倒计时（pi CountdownTimer 两回调）──

    @Test
    void countdownTickSuffixesTitleAndExpiryCancels() {
        var result = new CompletableFuture<String>();
        var overlay = overlay(List.of("a"), result);

        overlay.countdownTick(3);
        assertThat(visibleText(overlay.layout())).contains(" Pick (3s)");

        overlay.countdownExpired();     // pi onExpire＝取消
        assertThat(overlay.isDone()).isTrue();
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue(null);
    }
}
