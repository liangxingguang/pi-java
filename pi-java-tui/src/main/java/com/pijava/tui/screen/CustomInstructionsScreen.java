package com.pijava.tui.screen;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 自定义摘要指令输入框（docs/18 §3.12，pi {@code showExtensionEditor(
 * "Custom summarization instructions")}，interactive-mode.ts:5603-5610）。
 *
 * <p>标题逐字【模型可见】；EditorComponent 弱形状（窄差 D39）。Enter 确认、
 * Esc 取消（overlay 先拦 {@code isCancel} 再转发编辑器——编辑器自身不消费
 * Esc；Enter 的 submit 也由 overlay 触发，与 {@code PiTuiApp} 的主编辑器
 * 分工同形）。空白确认视同取消（pi 的空串指令无意义，回三选更符合预期）。</p>
 */
public final class CustomInstructionsScreen implements ScreenOverlay {

    private final EditorComponent editor = new EditorComponent();
    private final Consumer<String> onResult;
    private boolean done;
    private boolean cancelled;
    private String resultText;

    /**
     * @param onResult 定案回调；Esc（或空白确认）⇒ {@code null}（pi 的取消臂）
     */
    public CustomInstructionsScreen(Consumer<String> onResult) {
        this.onResult = onResult;
        editor.onSubmit(text -> resultText = text);
    }

    /** Handle editor keys; returns true when consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isCancel()) {
            cancelled = true;
            done = true;
            return true;
        }
        if (event.isConfirm()) {
            editor.submit(); // 空白不触发 handler ⇒ resultText 留 null（视同取消）
            done = true;
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
        onResult.accept(cancelled ? null : resultText);
    }

    @Override
    public Element render() {
        return TamboUIAdapter.column(
            TamboUIAdapter.text("Custom summarization instructions").length(1),
            editor.render());
    }
}
