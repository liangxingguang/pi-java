package com.pijava.tui.screen;

import java.util.List;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.SelectList;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 树导航摘要三选对话框（docs/18 §3.12，pi {@code showExtensionSelector(
 * "Summarize branch?", …)}，interactive-mode.ts:5592-5615）。
 *
 * <p>行文案逐字【模型可见】；SelectList 弱形状（pi 是带边框的 selector 组件，
 * 窄差 D39）。结局经 {@code onChoice} 回传：Esc ⇒ null（pi 的 undefined）。</p>
 */
public final class SummaryChoiceScreen implements ScreenOverlay {

    /** 三选取值（行序即 pi 的选项序）。 */
    public enum Choice { NO_SUMMARY, SUMMARIZE, CUSTOM }

    private static final String NO_SUMMARY = "No summary";
    private static final String SUMMARIZE = "Summarize";
    private static final String CUSTOM = "Summarize with custom prompt";

    private final SelectList<String> list =
        new SelectList<>(List.of(NO_SUMMARY, SUMMARIZE, CUSTOM), s -> s);
    private final Consumer<Choice> onChoice;

    /**
     * @param onChoice 定案回调；Esc ⇒ {@code null}（pi 重开树选择器臂）
     */
    public SummaryChoiceScreen(Consumer<Choice> onChoice) {
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
        list.selected().ifPresentOrElse(row -> onChoice.accept(switch (row) {
            case SUMMARIZE -> Choice.SUMMARIZE;
            case CUSTOM -> Choice.CUSTOM;
            default -> Choice.NO_SUMMARY;
        }), () -> onChoice.accept(null));
    }

    @Override
    public Element render() {
        return TamboUIAdapter.column(
            TamboUIAdapter.text("Summarize branch?").length(1),
            list.render());
    }
}
