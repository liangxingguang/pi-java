package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：modal-editor 验收——模式切换、hjkl 导航、x 删除、边框模式标签。 */
class ModalEditorExtensionTest {

    private static ExtensionUiHostEnv env() {
        var renders = new AtomicInteger();
        return new ExtensionUiHostEnv(() -> 80, () -> 24, renders::incrementAndGet,
            (slot, text) -> text);
    }

    private static KeyStroke ch(char c) {
        return KeyStroke.of(String.valueOf(c), false, false, false);
    }

    private static void type(ModalEditorExtension.ModalEditor editor, String text) {
        text.chars().forEach(c -> editor.handleInput(ch((char) c)));
    }

    @Test
    void normalKeyMapMatchesPi() {
        assertThat(ModalEditorExtension.NORMAL_KEYS).containsKeys('h', 'j', 'k', 'l',
            '0', '$', 'x');
        assertThat(ModalEditorExtension.NORMAL_KEYS.get('h'))
            .isEqualTo(KeyStroke.of("left", false, false, false));
        assertThat(ModalEditorExtension.NORMAL_KEYS.get('0'))
            .isEqualTo(KeyStroke.of("a", true, false, false));
        assertThat(ModalEditorExtension.NORMAL_KEYS.get('$'))
            .isEqualTo(KeyStroke.of("e", true, false, false));
        assertThat(ModalEditorExtension.NORMAL_KEYS.get('x'))
            .isEqualTo(KeyStroke.of("delete", false, false, false));
        assertThat(ModalEditorExtension.Mode.NORMAL.label()).isEqualTo(" NORMAL ");
        assertThat(ModalEditorExtension.Mode.INSERT.label()).isEqualTo(" INSERT ");
    }

    @Test
    void insertModeIsDefaultAndPassesInputThrough() {
        var w = new W3Context();
        new ModalEditorExtension().register(w.ctx);
        w.fire(ExtensionEventSpec.SESSION_START, null);

        var editor = (ModalEditorExtension.ModalEditor) w.ui.editorFactories().get(0)
            .create(env(), new KeybindingsManager());

        assertThat(editor.mode()).isEqualTo(ModalEditorExtension.Mode.INSERT);
        type(editor, "ab");
        assertThat(editor.getText()).isEqualTo("ab");
        var lines = editor.render(20);
        assertThat(lines.get(lines.size() - 1)).endsWith(" INSERT ");
    }

    @Test
    void escapeTogglesAndHjklNavigate() {
        var editor = newEditor();

        type(editor, "ab");
        editor.handleInput(KeyStroke.of("esc", false, false, false));
        assertThat(editor.mode()).isEqualTo(ModalEditorExtension.Mode.NORMAL);
        var normalLines = editor.render(20);
        assertThat(normalLines.get(normalLines.size() - 1)).endsWith(" NORMAL ");

        // h 左移一格；NORMAL 里的可打印字符不落文本
        editor.handleInput(ch('h'));
        assertThat(editor.cursor()).isEqualTo(1);
        editor.handleInput(ch('q'));
        assertThat(editor.getText()).isEqualTo("ab");

        // i 回插入模式，在光标处插入
        editor.handleInput(ch('i'));
        assertThat(editor.mode()).isEqualTo(ModalEditorExtension.Mode.INSERT);
        editor.handleInput(ch('X'));
        assertThat(editor.getText()).isEqualTo("aXb");

        // 再进 NORMAL，x 删除光标处字符
        editor.handleInput(KeyStroke.of("esc", false, false, false));
        editor.handleInput(ch('x'));
        assertThat(editor.getText()).isEqualTo("aX");
    }

    @Test
    void appendMovesRightThenEntersInsert() {
        var editor = newEditor();

        type(editor, "abc");
        editor.handleInput(KeyStroke.of("esc", false, false, false));
        editor.handleInput(ch('0'));
        assertThat(editor.cursor()).isZero();
        editor.handleInput(ch('a'));

        assertThat(editor.mode()).isEqualTo(ModalEditorExtension.Mode.INSERT);
        // a = 插入模式 + 先右移一格
        assertThat(editor.cursor()).isEqualTo(1);
        editor.handleInput(ch('Z'));
        assertThat(editor.getText()).isEqualTo("aZbc");
    }

    @Test
    void escapeInNormalModePassesThrough() {
        var editor = newEditor();

        editor.handleInput(KeyStroke.of("esc", false, false, false));
        assertThat(editor.mode()).isEqualTo(ModalEditorExtension.Mode.NORMAL);
        editor.handleInput(KeyStroke.of("esc", false, false, false));
        // 下传给基类：无副作用（基类无 escape 语义），模式仍是 NORMAL
        assertThat(editor.mode()).isEqualTo(ModalEditorExtension.Mode.NORMAL);
    }

    private static ModalEditorExtension.ModalEditor newEditor() {
        var w = new W3Context();
        new ModalEditorExtension().register(w.ctx);
        w.fire(ExtensionEventSpec.SESSION_START, null);
        assertThat(w.ui.editorFactories()).hasSize(1);
        var editor = (ModalEditorExtension.ModalEditor) w.ui.editorFactories().get(0)
            .create(env(), new KeybindingsManager());
        assertThat(editor.render(20)).hasSize(3);
        return editor;
    }

    @Test
    void labelIsAppendedToBottomBorder() {
        var editor = newEditor();
        type(editor, "x");
        var lines = editor.render(20);

        assertThat(lines.get(2)).isEqualTo(
            ExampleText.truncate(lines.get(0), 20 - " INSERT ".length()) + " INSERT ");
        assertThat(ExampleText.width(lines.get(2))).isEqualTo(20);
    }
}
