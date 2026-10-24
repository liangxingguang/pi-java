package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 批 17（T4，docs/28 §1.3）：pi {@code ExtensionEditorComponent}
 * （extension-editor.ts，141 行）的 java 落形——扩展 {@code ctx.ui.editor} 的
 * 多行编辑对话框。
 *
 * <p>逐字语义：标题 accent 不加粗；内嵌完整编辑器（{@link EditorComponent}，
 * prefill 预填）；键序 cancel（esc/ctrl+c）⇒ ctrl+g 外部编辑器往返 ⇒ 其余转发
 * 编辑器（Enter＝提交由 overlay 拦截——java 的 Enter 归应用壳，EditorComponent
 * 自身不吃）；hint 四段 {@code submit / newline / cancel / external editor}。
 * 外部编辑器往返的 alt 屏序列与进程 spawn 由宿主编排（PiTuiApp 经 host 注入）。</p>
 */
public final class ExtensionEditorOverlay implements ScreenOverlay, ExtensionDialog {

    /** 一行渲染文本的一个样式段（测试断言面）。 */
    record Span(String text, Style style) {}

    private final String title;
    private final KeybindingsManager keys;
    private final IntSupplier terminalCols;
    private final CompletableFuture<String> result;
    private final EditorComponent editor = new EditorComponent();
    /** 外部编辑器往返：（命令, 当前内容) ⇒ 读回内容（空 ⇒ 内容不动，pi 非 complete 臂）。 */
    private final BiFunction<String, String, Optional<String>> externalEditor;
    /** 已解析的编辑器命令（pi :55-59 回落链由宿主构造期解析）。 */
    private final String externalEditorCommand;
    private volatile boolean done;
    /** cancel 可从宿主复位线程进入（⇒ volatile；pi 单线程无此形态）。 */
    private volatile boolean cancelled;

    /**
     * @param title                 标题
     * @param prefill               预填内容（null ⇒ 空）
     * @param keys                  键位表
     * @param terminalCols          终端列数源
     * @param externalEditorCommand 外部编辑器命令（宿主已按回落链解析，恒非空）
     * @param externalEditor        外部编辑器往返（宿主注入）
     * @param result                结局 future（apply 时完成：文本或 null）
     */
    public ExtensionEditorOverlay(String title, String prefill,
                                  KeybindingsManager keys, IntSupplier terminalCols,
                                  String externalEditorCommand,
                                  BiFunction<String, String, Optional<String>> externalEditor,
                                  CompletableFuture<String> result) {
        this.title = title;
        this.keys = keys;
        this.terminalCols = terminalCols;
        this.externalEditorCommand = externalEditorCommand;
        this.externalEditor = externalEditor;
        this.result = result;
        if (prefill != null && !prefill.isEmpty()) {
            editor.setText(prefill);
        }
    }

    // ── 键分派（pi handleInput :117-133 序逐字）────────────────

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        var stroke = TamboUIAdapter.toStroke(event);
        // pi :119-122：cancel 最优先。
        if (keys.matches(KeybindingsManager.SELECT_CANCEL, stroke)) {
            cancel();
            return true;
        }
        // pi :125-128：ctrl+g 外部编辑器往返（complete 臂回写）。
        if (keys.matches(KeybindingsManager.EXTERNAL_EDITOR, stroke)) {
            externalEditor.apply(externalEditorCommand, editor.getText())
                .ifPresent(editor::setText);
            return true;
        }
        // java 特有拦截（Enter 归应用壳——pi 里 Enter 由 Editor 内部 onSubmit 吃）：
        // newline 键 ⇒ 插换行；confirm/Enter ⇒ 提交（pi 的 editor.onSubmit 对位）。
        if (keys.matches(KeybindingsManager.INPUT_NEW_LINE, stroke)
                || TamboUIAdapter.isNewlineEnter(event)) {
            editor.insertNewline();
            return true;
        }
        if (keys.matches(KeybindingsManager.SELECT_CONFIRM, stroke)
                || TamboUIAdapter.isSendEnter(event)) {
            done = true;    // pi：editor.onSubmit(text)——无 blank 门
            return true;
        }
        editor.onKeyEvent(event);
        return true;
    }

    /** 取消（Esc/ctrl+c、宿主复位同走此口；pi editor 对话框无 timeout 面）。 */
    @Override
    public void cancel() {
        cancelled = true;
        done = true;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        result.complete(cancelled ? null : editor.getText());
    }

    /** 结局 future（宿主复位时补完成——幂等）。 */
    @Override
    public CompletableFuture<String> resultFuture() {
        return result;
    }

    /** 编辑器当前内容（测试探针）。 */
    String editorText() {
        return editor.getText();
    }

    // ── 渲染（pi 构造器 :64-111 子件序逐字）───────────────────

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
        children.add(editor.renderBare());   // pi :90：内嵌 Editor 直挂
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

    /** border ⇒ 空行 ⇒ 标题（accent 不加粗）⇒ 空行（编辑器由 render 挂）。 */
    private List<List<Span>> headerLayout(int width) {
        var rows = new ArrayList<List<Span>>();
        rows.add(borderRow(width));
        rows.add(List.of());
        rows.add(List.of(new Span(" " + title, SemanticColors.accent())));
        rows.add(List.of());
        return rows;
    }

    /** pi DynamicBorder：{@code "─".repeat(max(1, width))}，border 色（≈ DIM）。 */
    private static List<Span> borderRow(int width) {
        return List.of(new Span("─".repeat(Math.max(1, width)), SemanticColors.dim()));
    }

    /** pi hint 四段逐字（:100-106）。 */
    private List<Span> hintRow() {
        return List.of(
            new Span(" " + KeybindingHints.keyText(
                keys.strokeFor(KeybindingsManager.SELECT_CONFIRM)), SemanticColors.dim()),
            new Span(" submit", SemanticColors.muted()),
            new Span("  ", SemanticColors.plain()),
            new Span(KeybindingHints.keyText(
                keys.strokeFor(KeybindingsManager.INPUT_NEW_LINE)), SemanticColors.dim()),
            new Span(" newline", SemanticColors.muted()),
            new Span("  ", SemanticColors.plain()),
            new Span(KeybindingHints.keyText(
                keys.strokeFor(KeybindingsManager.SELECT_CANCEL)), SemanticColors.dim()),
            new Span(" cancel", SemanticColors.muted()),
            new Span("  ", SemanticColors.plain()),
            new Span(KeybindingHints.keyText(
                keys.strokeFor(KeybindingsManager.EXTERNAL_EDITOR)), SemanticColors.dim()),
            new Span(" external editor", SemanticColors.muted()));
    }
}
