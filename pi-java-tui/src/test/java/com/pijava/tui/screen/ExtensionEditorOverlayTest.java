package com.pijava.tui.screen;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.coding.agent.core.ExternalEditorSupport;
import com.pijava.coding.agent.core.KeybindingsManager;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T4，docs/28 §7-7 后半）：扩展编辑对话框——pi extension-editor.ts 逐字
 * 对拍：prefill 预填、Enter 提交（无 blank 门）、shift+enter/ctrl+j 换行、esc 取消、
 * ctrl+g 外部编辑器往返（complete 臂回写；非 complete 不动）。
 */
class ExtensionEditorOverlayTest {

    private final KeybindingsManager keys = new KeybindingsManager();

    private ExtensionEditorOverlay overlay(String prefill, CompletableFuture<String> result,
                                           java.util.function.BiFunction<String, String,
                                               ExternalEditorSupport.Result> external) {
        return new ExtensionEditorOverlay("Compose", prefill, keys, () -> 80,
            "notepad", external, result);
    }

    private static ExternalEditorSupport.Result failed(String message) {
        return new ExternalEditorSupport.Result.Failed(message);
    }

    @Test
    void prefillAndEnterSubmits() {
        var result = new CompletableFuture<String>();
        var overlay = overlay("line1", result, (cmd, c) -> failed("no external editor"));
        assertThat(overlay.editorText()).isEqualTo("line1");
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue("line1");
    }

    @Test
    void shiftEnterInsertsNewlineEnterSubmits() {
        var result = new CompletableFuture<String>();
        var overlay = overlay(null, result, (cmd, c) -> failed("no external editor"));
        overlay.onKeyEvent(KeyEvent.ofChar('a'));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT));
        overlay.onKeyEvent(KeyEvent.ofChar('b'));
        assertThat(overlay.editorText()).isEqualTo("a\nb");
        // ctrl+j 同臂（tui.input.newLine 第二默认键）
        overlay.onKeyEvent(KeyEvent.ofChar('j', KeyModifiers.CTRL));
        overlay.onKeyEvent(KeyEvent.ofChar('c'));
        assertThat(overlay.editorText()).isEqualTo("a\nb\nc");
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue("a\nb\nc");
    }

    @Test
    void escapeCancelsToNull() {
        var result = new CompletableFuture<String>();
        var overlay = overlay("draft", result, (cmd, c) -> failed("no external editor"));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        overlay.apply(null, s -> { });
        assertThat(result).isCompletedWithValue(null);
    }

    @Test
    void ctrlGExternalEditorRoundtripWritesBackOnComplete() {
        var result = new CompletableFuture<String>();
        var seenCommand = new AtomicReference<String>();
        var overlay = overlay("local", result, (cmd, content) -> {
            seenCommand.set(cmd);
            // complete 臂回写
            return new ExternalEditorSupport.Result.Complete(content + " +external");
        });
        overlay.onKeyEvent(KeyEvent.ofChar('g', KeyModifiers.CTRL));
        assertThat(seenCommand).hasValue("notepad");
        assertThat(overlay.editorText()).isEqualTo("local +external");
        assertThat(overlay.warningText()).isNull();
        assertThat(overlay.isDone()).isFalse();   // 往返不结束对话框（pi finally 回屏）
    }

    /** docs/64（pi extension-editor.ts:136）：失败 ⇒ 框内 warning，内容不动。 */
    @Test
    void ctrlGFailureShowsWarningAndKeepsContent() {
        var overlay = overlay("keep", new CompletableFuture<>(),
            (cmd, c) -> failed("External editor exited with code 1; prompt unchanged"));

        overlay.onKeyEvent(KeyEvent.ofChar('g', KeyModifiers.CTRL));

        assertThat(overlay.editorText()).isEqualTo("keep");
        assertThat(overlay.warningText())
            .isEqualTo("External editor exited with code 1; prompt unchanged");
    }

    /** 下一次成功的往返才清 warning（pi 无定时清除）。 */
    @Test
    void aLaterSuccessClearsTheWarning() {
        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        var overlay = overlay("keep", new CompletableFuture<>(), (cmd, content) ->
            attempt.getAndIncrement() == 0
                ? failed("Failed to launch external editor: notepad")
                : new ExternalEditorSupport.Result.Complete(content + "!"));

        overlay.onKeyEvent(KeyEvent.ofChar('g', KeyModifiers.CTRL));
        assertThat(overlay.warningText()).isNotNull();

        overlay.onKeyEvent(KeyEvent.ofChar('g', KeyModifiers.CTRL));
        assertThat(overlay.warningText()).isNull();
        assertThat(overlay.editorText()).isEqualTo("keep!");
    }
}
