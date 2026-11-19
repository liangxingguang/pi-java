package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/modal-editor.ts:1-85} 移植（docs/27 W5）——
 * 类 vim 的模态编辑：{@code Escape} 进 NORMAL，{@code i}/{@code a} 回 INSERT，
 * {@code hjkl}/{@code 0}/{@code $}/{@code x} 在 NORMAL 里导航与删除。
 *
 * <p>逐字对位：NORMAL 模式的按键映射成"等价的终端序列"后交给基类编辑器——
 * java 的按键已是中性的 {@link KeyStroke}，故映射成同义键
 * （{@code h}→{@code left}、{@code 0}→{@code ctrl+a}、{@code $}→{@code ctrl+e}、
 * {@code x}→{@code delete}）。NORMAL 里的可打印字符被吞掉；控制序列（ctrl+c…）
 * 继续下传。底边框尾部贴 {@code " NORMAL "}/{@code " INSERT "} 模式标签。</p>
 *
 * <p>基类见 {@link ExampleEditor}（文本级 CustomEditor 对位）。</p>
 */
public final class ModalEditorExtension implements PiExtension {

    /** pi L16-26：NORMAL 模式的键 ⇒ 等价移动/删除键（{@code i}/{@code a} 是模式切换，不入表）。 */
    static final Map<Character, KeyStroke> NORMAL_KEYS = Map.of(
        'h', KeyStroke.of("left", false, false, false),
        'j', KeyStroke.of("down", false, false, false),
        'k', KeyStroke.of("up", false, false, false),
        'l', KeyStroke.of("right", false, false, false),
        '0', KeyStroke.of("a", true, false, false),
        '$', KeyStroke.of("e", true, false, false),
        'x', KeyStroke.of("delete", false, false, false));

    /** pi L29：两种模式与底边框标签（逐字，含两侧空格）。 */
    enum Mode {
        NORMAL(" NORMAL "), INSERT(" INSERT ");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @Override
    public String name() {
        return "modal-editor";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            c.ui().setEditorComponent((env, keybindings) -> new ModalEditor(env));
            return null;
        });
    }

    /** pi L28-79：{@code class ModalEditor extends CustomEditor}。 */
    static final class ModalEditor extends ExampleEditor {

        private Mode mode = Mode.INSERT;

        ModalEditor(ExtensionUiHostEnv env) {
            super(ExampleEditor.colorizeOf(env));
        }

        /** 当前模式（测试探针）。 */
        Mode mode() {
            return mode;
        }

        @Override
        public void handleInput(KeyStroke key) {
            // Escape：insert → normal；normal → 下传（中止 agent 等）
            if ("esc".equals(key.key()) && !key.ctrl() && !key.alt()) {
                if (mode == Mode.INSERT) {
                    mode = Mode.NORMAL;
                    return;
                }
                super.handleInput(key);
                return;
            }
            // Insert 模式：全部下传
            if (mode == Mode.INSERT) {
                super.handleInput(key);
                return;
            }
            var ch = plainChar(key);
            if (ch != null && (ch == 'i' || ch == 'a')) {
                mode = Mode.INSERT;
                if (ch == 'a') {
                    super.handleInput(KeyStroke.of("right", false, false, false));
                }
                return;
            }
            var mapped = ch == null ? null : NORMAL_KEYS.get(ch);
            if (mapped != null) {
                super.handleInput(mapped);
                return;
            }
            // 控制序列下传；可打印字符在 normal 模式吞掉
            if (ch != null) {
                return;
            }
            super.handleInput(key);
        }

        /** pi L67-78：底边框尾部贴模式标签。 */
        @Override
        public List<String> render(int width) {
            var lines = new ArrayList<>(super.render(width));
            if (lines.isEmpty()) {
                return lines;
            }
            var label = mode.label();
            var last = lines.size() - 1;
            if (ExampleText.width(lines.get(last)) >= label.length()) {
                lines.set(last,
                    ExampleText.truncate(lines.get(last), width - label.length()) + label);
            }
            return lines;
        }

        /** 无修饰的单字符键（pi 的 {@code data.length === 1}）。 */
        private static Character plainChar(KeyStroke key) {
            var name = key.key();
            if (name == null || name.length() != 1 || key.ctrl() || key.alt()) {
                return null;
            }
            return name.charAt(0);
        }
    }
}
