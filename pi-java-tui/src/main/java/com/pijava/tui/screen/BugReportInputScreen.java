package com.pijava.tui.screen;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * {@code /bug} 的描述输入屏（批 15 T7，pi {@code input(context, "Report a bug",
 * DISCLAIMER + "\n\nWhat went wrong? (optional)", initialHint)} 对位，
 * interactive-mode bug-report.ts:60-64）。
 *
 * <p>{@code CustomInstructionsScreen} 同构＋描述区（标题下、编辑框上）＋初始值
 * （{@code /bug <description>} 的 hint 预填）。与摘要指令屏的差异：<b>空白确认
 * 是合法答案</b>（hint 本可选，pi 的 {@code hint || undefined}）——Enter 直接取
 * 编辑器文本，不走 submit 的空白门。Esc ⇒ {@code null}（pi 的取消臂）。
 * 外部编辑器缝不做（pi ExtensionEditorComponent 带；窄差 D88）。</p>
 */
public final class BugReportInputScreen implements ScreenOverlay {

    private final EditorComponent editor = new EditorComponent();
    private final String title;
    private final String description;
    private final Consumer<String> onResult;
    private boolean done;
    private boolean cancelled;

    /**
     * @param title        屏标题（pi 逐字）
     * @param description  描述区文本（多行，逐行渲染）
     * @param initialValue 初始值（{@code /bug <description>} 预填；null/空 ⇒ 空框）
     * @param onResult     定案回调；Esc ⇒ {@code null}
     */
    public BugReportInputScreen(String title, String description,
                                String initialValue, Consumer<String> onResult) {
        this.title = title;
        this.description = description;
        this.onResult = onResult;
        if (initialValue != null && !initialValue.isEmpty()) {
            editor.setText(initialValue);
        }
    }

    /** Handle editor keys; returns true when consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isCancel()) {
            cancelled = true;
            done = true;
            return true;
        }
        if (event.isConfirm()) {
            done = true; // 空白是合法答案（hint 可选）——定案时现取文本。
            return true;
        }
        editor.onKeyEvent(event);
        return true;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        onResult.accept(cancelled ? null : editor.getText());
    }

    @Override
    public Element render() {
        var rows = new java.util.ArrayList<Element>();
        rows.add(TamboUIAdapter.text(title));
        for (var line : description.split("\n", -1)) {
            rows.add(TamboUIAdapter.text(line));
        }
        rows.add(editor.render());
        return TamboUIAdapter.column(rows);
    }
}
