package com.pijava.tui.screen;

import java.util.List;
import java.util.function.Consumer;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.builtin.ThinkingCommands;
import com.pijava.tui.component.SelectList;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * {@code /thinking}（空参）的思考级别选择器（批 15 T8，pi
 * {@code showThinkingSelector} 对位——interactive-mode.ts:5148）。
 *
 * <p>裁决⑥简版：SelectList＋当前级预选＋Enter 即设（会话级，persist=false）
 * ＋Esc 取消；pi 组件的 persist 第二键（存默认）与 DEFAULT 标记属 S8 域不做
 * （窄差 D84）。选中后状态行 {@code Thinking level: <level>}（pi
 * {@code selectThinkingLevel} 的 showStatus 逐字【用户可见】）。</p>
 */
public final class ThinkingLevelScreen implements ScreenOverlay {

    private final SelectList<ModelThinkingLevel> list;
    private final AgentSession session;
    private final Consumer<String> statusSink;

    /**
     * @param session    当前会话（可用集、当前级预选、直设落点）
     * @param statusSink 状态行落点（PiTuiApp 传 {@code chatScreen::appendSystemText}）
     */
    public ThinkingLevelScreen(AgentSession session, Consumer<String> statusSink) {
        this.session = session;
        this.statusSink = statusSink;
        var available = ThinkingCommands.availableLevels(session);
        this.list = new SelectList<>(available, ModelThinkingLevel::label);
        // 当前级预选（pi 选择器初始选中当前级）。
        var current = session.harness().getThinkingLevel();
        list.selectItem(current::equals);
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
            return; // Esc：pi 的 onCancel 只关选择器
        }
        list.selected().ifPresent(level -> {
            this.session.setThinkingLevel(level);
            statusSink.accept("Thinking level: " + level.label());
        });
    }

    @Override
    public Element render() {
        return TamboUIAdapter.column(
            TamboUIAdapter.text("Select thinking level"),
            list.render());
    }
}
