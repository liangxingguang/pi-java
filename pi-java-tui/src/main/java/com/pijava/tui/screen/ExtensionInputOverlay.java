package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;

/**
 * 批 17（T4，docs/28 §1.3）：pi {@code ExtensionInputComponent}
 * （extension-input.ts，94 行）的 java 落形——扩展 {@code ctx.ui.input} 的
 * 单行输入对话框。
 *
 * <p>与 selector 的差异逐字：标题 accent <b>不加粗</b>（pi 少一处
 * {@code theme.bold}）；hint 行 {@code <confirm> submit  <cancel> cancel}；
 * confirm/Enter ⇒ 提交输入框当前值（空白也提交——pi 无 blank 门）；其余键转发
 * 内部输入框。pi 构造器收 {@code _placeholder} 但<b>从不使用</b>
 * （extension-input.ts:38——pi 侧既存事实，java 同形忽略，非窄差）。</p>
 *
 * <p>单行输入是 {@link TextAreaState}＋{@code EditorElement} 的窄化驱动
 * （字符/退格/删除/方向/Home/End——pi {@code Input} 组件的核心面；kill-ring 等
 * 编辑器高级键不在 pi Input 内，不移植）。</p>
 */
public final class ExtensionInputOverlay implements ScreenOverlay, ExtensionDialog {

    /** 一行渲染文本的一个样式段（测试断言面）。 */
    record Span(String text, Style style) {}

    private final String baseTitle;
    private final KeybindingsManager keys;
    private final IntSupplier terminalCols;
    private final CompletableFuture<String> result;
    private final TextAreaState input = new TextAreaState();
    private volatile int countdownSeconds = -1;
    private volatile boolean done;
    /** cancel 可从倒计时/abort 线程进入（⇒ volatile；pi 单线程无此形态）。 */
    private volatile boolean cancelled;

    /**
     * @param title        标题
     * @param placeholder  pi 的 {@code _placeholder}——收下但不使用（pi 同）
     * @param keys         键位表
     * @param terminalCols 终端列数源
     * @param result       结局 future（apply 时完成：输入值或 null）
     */
    public ExtensionInputOverlay(String title, String placeholder,
                                 KeybindingsManager keys, IntSupplier terminalCols,
                                 CompletableFuture<String> result) {
        this.baseTitle = title;
        this.keys = keys;
        this.terminalCols = terminalCols;
        this.result = result;
    }

    // ── 键分派（pi handleInput :82-91 逐字）──────────────────

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        var stroke = TamboUIAdapter.toStroke(event);
        if (keys.matches(KeybindingsManager.SELECT_CONFIRM, stroke)
                || TamboUIAdapter.isSendEnter(event)) {
            done = true;    // pi :85-86：提交输入框当前值（无 blank 门）
        } else if (keys.matches(KeybindingsManager.SELECT_CANCEL, stroke)) {
            cancel();
        } else {
            forwardToInput(event);   // pi :90：input.handleInput(keyData)
        }
        return true;
    }

    /** pi {@code Input} 的核心编辑面（单行——换行符不入文）。 */
    private void forwardToInput(KeyEvent event) {
        if (event.hasCtrl() || event.hasAlt()) {
            return; // pi Input 无 ctrl/alt 绑面（同 EditorComponent 的丢弃序）
        }
        switch (event.code()) {
            case KeyCode.CHAR -> {
                var text = event.string();
                if (text != null && !text.isEmpty() && !"\r".equals(text) && !"\n".equals(text)) {
                    input.insert(text);
                }
            }
            case KeyCode.BACKSPACE -> input.deleteBackward();
            case KeyCode.DELETE -> input.deleteForward();
            case KeyCode.LEFT -> input.moveCursorLeft();
            case KeyCode.RIGHT -> input.moveCursorRight();
            case KeyCode.HOME -> input.moveCursorToLineStart();
            case KeyCode.END -> input.moveCursorToLineEnd();
            default -> { }
        }
    }

    /** 取消（Esc/ctrl+c、timeout 到期、宿主复位同走此口）。 */
    @Override
    public void cancel() {
        cancelled = true;
        done = true;
    }

    /** pi CountdownTimer 的 onTick（标题 {@code "{title} ({Ns})"}，不加粗）。 */
    @Override
    public void countdownTick(int remainingSeconds) {
        countdownSeconds = remainingSeconds;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        result.complete(cancelled ? null : input.text());
    }

    /** 结局 future（宿主复位时补完成——幂等）。 */
    @Override
    public CompletableFuture<String> resultFuture() {
        return result;
    }

    /** 输入框当前值（测试探针）。 */
    String inputText() {
        return input.text();
    }

    // ── 渲染（pi 构造器 :44-71 子件序逐字）────────────────────

    @Override
    public Element render() {
        var children = new ArrayList<Element>();
        int width = Math.max(1, terminalCols.getAsInt());
        for (var row : headerLayout(width)) {
            if (row.isEmpty()) {
                children.add(TamboUIAdapter.spacer(1));
            } else {
                children.add(TamboUIAdapter.richText(Text.from(Line.from(row.stream()
                    .map(s -> new dev.tamboui.text.Span(s.text(), s.style()))
                    .toList()))));
            }
        }
        // 输入行（pi :66-68：Input 组件直挂）。
        children.add(TamboUIAdapter.editorElement(input));
        children.add(TamboUIAdapter.spacer(1));
        children.add(TamboUIAdapter.richText(Text.from(Line.from(hintRow().stream()
            .map(s -> new dev.tamboui.text.Span(s.text(), s.style()))
            .toList()))));
        children.add(TamboUIAdapter.spacer(1));
        children.add(TamboUIAdapter.richText(Text.from(Line.from(
            borderRow(width).stream()
                .map(s -> new dev.tamboui.text.Span(s.text(), s.style()))
                .toList()))));
        return TamboUIAdapter.column(children);
    }

    /** border ⇒ 空行 ⇒ 标题（accent <b>不加粗</b>）⇒ 空行（输入行由 render 挂）。 */
    private List<List<Span>> headerLayout(int width) {
        var rows = new ArrayList<List<Span>>();
        rows.add(borderRow(width));
        rows.add(List.of());
        var title = countdownSeconds >= 0
            ? baseTitle + " (" + countdownSeconds + "s)" : baseTitle;
        rows.add(List.of(new Span(" " + title, SemanticColors.ACCENT)));
        rows.add(List.of());
        return rows;
    }

    /** pi DynamicBorder：{@code "─".repeat(max(1, width))}，border 色（≈ DIM）。 */
    private static List<Span> borderRow(int width) {
        return List.of(new Span("─".repeat(Math.max(1, width)), SemanticColors.DIM));
    }

    /** pi hint 行逐字：{@code keyHint(confirm,"submit") + "  " + keyHint(cancel,"cancel")}。 */
    private List<Span> hintRow() {
        return List.of(
            new Span(" " + KeybindingHints.keyText(
                keys.strokeFor(KeybindingsManager.SELECT_CONFIRM)), SemanticColors.DIM),
            new Span(" submit", SemanticColors.MUTED),
            new Span("  ", SemanticColors.PLAIN),
            new Span(KeybindingHints.keyText(
                keys.strokeFor(KeybindingsManager.SELECT_CANCEL)), SemanticColors.DIM),
            new Span(" cancel", SemanticColors.MUTED));
    }
}
