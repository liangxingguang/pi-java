package com.pijava.tui.screen;

import java.util.List;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.SelectList;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * {@code /bug} 的选择屏（批 15 T7，pi {@code choose(context, title, options,
 * description)} 对位——interactive-mode bug-report.ts 的
 * transcript/summary/delivery/upload-fallback 四调用点同一件）。
 *
 * <p>{@code SummaryChoiceScreen} 同构＋描述区（多行 muted 文本，逐行渲染）。
 * Esc ⇒ {@code null}（pi 的 undefined ⇒ flow 取消臂）。</p>
 */
public final class BugReportChoiceScreen implements ScreenOverlay {

    private final SelectList<String> list;
    private final String title;
    private final String description;
    private final Consumer<String> onChoice;

    /**
     * @param title       屏标题（pi 逐字）
     * @param options     选项行（行序即 pi 的选项序）
     * @param description 描述区文本（多行）
     * @param onChoice    定案回调；Esc ⇒ {@code null}
     */
    public BugReportChoiceScreen(String title, List<String> options,
                                 String description, Consumer<String> onChoice) {
        this.list = new SelectList<>(options, s -> s);
        this.title = title;
        this.description = description;
        this.onChoice = onChoice;
    }

    /** Handle selector keys; returns true when consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        return list.onKeyEvent(event);
    }

    @Override
    public boolean isDone() {
        return list.confirmed() || list.cancelled();
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        if (list.cancelled()) {
            onChoice.accept(null);
            return;
        }
        list.selected().ifPresentOrElse(onChoice, () -> onChoice.accept(null));
    }

    @Override
    public Element render() {
        var rows = new java.util.ArrayList<Element>();
        rows.add(TamboUIAdapter.text(title));
        for (var line : description.split("\n", -1)) {
            rows.add(TamboUIAdapter.text(line));
        }
        rows.add(list.render());
        return TamboUIAdapter.column(rows);
    }
}
