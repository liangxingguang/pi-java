package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;

/**
 * pi {@code CustomEditor}（{@code @earendil-works/pi-coding-agent}）在示例层的
 * <b>文本级</b>最小对位（docs/27 W5 的三件编辑器示例共用）。
 *
 * <p>pi 的 {@code CustomEditor} 是 TUI 富组件（边框、换行、光标、补全、粘贴折叠、
 * 历史……）；java 的 {@link ExtensionComponent} 只有 {@code render(width)} 与
 * {@code handleInput(KeyStroke)}。三件示例真正消费的只是其中五件事：文本缓冲、
 * 光标移动、删除、换行与"把模式标签/状态贴到底边框"。本类只做这五件，
 * 使子类与 pi 源码一一对应：{@code super.handleInput(data)} ⇔
 * {@link #handleInput(KeyStroke)} 的基类实现，{@code this.borderColor(text)} ⇔
 * {@link #borderColor(String)}。</p>
 *
 * <p><b>窄差</b>：没有软换行/滚动/自动补全/多行历史；边框是单行 {@code ─} 填充，
 * 不比 pi 的转角字形。三件示例的验收只钉模式标签位置、着色文本与状态栏内容。</p>
 */
class ExampleEditor implements ExtensionComponent {

    /** 空串边框的填充字符（pi 边框主题的 {@code border} 字形）。 */
    static final String BORDER_GLYPH = "─";

    private final StringBuilder text = new StringBuilder();
    private final BiFunction<String, String, String> colorize;
    private Runnable requestRender = () -> { };
    private int cursor;

    ExampleEditor() {
        this(null);
    }

    /**
     * @param colorize 宿主主题色投影（{@code env.colorize}）；{@code null} ⇒ 恒等
     */
    ExampleEditor(BiFunction<String, String, String> colorize) {
        this.colorize = colorize;
    }

    /** 供子类从工厂环境构造（{@code setEditorComponent} 的 env）。 */
    static BiFunction<String, String, String> colorizeOf(ExtensionUiHostEnv env) {
        return env == null ? null : env.colorize();
    }

    /** pi {@code this.tui.requestRender()} 的注入点。 */
    void requestRender(Runnable requestRender) {
        this.requestRender = requestRender == null ? () -> { } : requestRender;
    }

    /** 请求一次重绘（pi {@code tui.requestRender()}）。 */
    protected void requestRender() {
        requestRender.run();
    }

    /** pi {@code this.getText()}。 */
    String getText() {
        return text.toString();
    }

    /** pi 编辑器文本替换（示例用；光标移到末尾）。 */
    void setText(String value) {
        text.setLength(0);
        text.append(value == null ? "" : value);
        cursor = text.length();
    }

    /** 光标码点下标（测试探针）。 */
    int cursor() {
        return cursor;
    }

    /** pi {@code this.borderColor(text)}：边框色槽。 */
    protected String borderColor(String value) {
        return colorize == null ? value : colorize.apply("borderColor", value);
    }

    /**
     * pi {@code super.handleInput(data)}：文本缓冲的编辑语义。
     *
     * <p>支持左右/上下/行首/行尾/退格/删除/换行与可打印字符；其余键忽略
     * （pi 的这一层还有补全与历史，本层不做）。</p>
     */
    @Override
    public void handleInput(KeyStroke key) {
        var name = key.key();
        if (name == null) {
            return;
        }
        switch (name) {
            case "left" -> moveBy(-1);
            case "right" -> moveBy(1);
            case "up" -> moveVertical(-1);
            case "down" -> moveVertical(1);
            default -> handleOther(key);
        }
    }

    private void handleOther(KeyStroke key) {
        var name = key.key();
        if (key.ctrl() && "a".equals(name)) {
            cursor = lineStart(cursor);
            return;
        }
        if (key.ctrl() && "e".equals(name)) {
            cursor = lineEnd(cursor);
            return;
        }
        if ("backspace".equals(name)) {
            deleteBackward();
            return;
        }
        if ("delete".equals(name)) {
            deleteForward();
            return;
        }
        if ("enter".equals(name)) {
            insert("\n");
            return;
        }
        if (!key.ctrl() && !key.alt() && name.length() == 1) {
            insert(name);
        }
    }

    /** 插入一段文本（pi 的可打印输入与换行）。 */
    protected void insert(String value) {
        text.insert(cursor, value);
        cursor += value.length();
    }

    /** pi {@code backspace}。 */
    protected void deleteBackward() {
        if (cursor == 0) {
            return;
        }
        var from = cursor - Character.charCount(text.codePointBefore(cursor));
        text.delete(from, cursor);
        cursor = from;
    }

    /** pi {@code delete}（{@code \x1b[3~} / {@code x}）。 */
    protected void deleteForward() {
        if (cursor >= text.length()) {
            return;
        }
        var to = cursor + Character.charCount(text.codePointAt(cursor));
        text.delete(cursor, to);
    }

    /** 左右移动（按码点，不跨行）。 */
    protected void moveBy(int delta) {
        if (delta < 0) {
            if (cursor == 0) {
                return;
            }
            cursor -= Character.charCount(text.codePointBefore(cursor));
            return;
        }
        if (cursor >= text.length()) {
            return;
        }
        cursor += Character.charCount(text.codePointAt(cursor));
    }

    /** 上下移动：保持列位（pi 的 hjkl 导航）。 */
    protected void moveVertical(int direction) {
        var start = lineStart(cursor);
        var column = cursor - start;
        int targetStart;
        if (direction < 0) {
            if (start == 0) {
                return;
            }
            targetStart = lineStart(start - 1);
        } else {
            var end = lineEnd(cursor);
            if (end >= text.length()) {
                return;
            }
            targetStart = end + 1;
        }
        var targetEnd = lineEnd(targetStart);
        cursor = Math.min(targetStart + column, targetEnd);
    }

    private int lineStart(int from) {
        for (int i = Math.min(from, text.length()) - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    private int lineEnd(int from) {
        for (int i = Math.max(0, Math.min(from, text.length())); i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                return i;
            }
        }
        return text.length();
    }

    /**
     * pi {@code CustomEditor.render} 的文本级形：上下两条 {@code ─} 边框夹住文本行。
     *
     * <p>行数恒 ≥ 2（空文本也有一条正文行），三件示例都按「首行/末行是边框」改写。</p>
     */
    @Override
    public List<String> render(int width) {
        var lines = new ArrayList<String>();
        var border = borderColor(BORDER_GLYPH.repeat(Math.max(0, width)));
        lines.add(border);
        for (var line : getText().split("\n", -1)) {
            lines.add(line);
        }
        lines.add(border);
        return lines;
    }
}
