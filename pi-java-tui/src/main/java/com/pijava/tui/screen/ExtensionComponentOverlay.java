package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 批 17（T5，docs/28 §3-T5.1）：{@code ctx.ui.custom} 的组件→模态层适配器
 * （pi {@code showExtensionCustom} 的换槽/overlay 两臂在 java 同路径——ScreenOverlay，
 * 裁决④/窄差 D95/D99）。
 *
 * <p>语义对位：{@code render()} ⇒ 组件 {@code render(width)} 行集（colorize 产物
 * 是 TamboUI markup——经 {@code markupText} 渲染）；按键 ⇒ 中性 KeyStroke 喂
 * {@code component.handleInput}；{@code isDone} ⇒ done 已调；{@code apply} 为空
 * （结果由 done 闭包直送——pi 的 {@code close(result)} 对位）。pi 非 overlay 臂的
 * 编辑器文本保存/恢复不移植——java 编辑器从未离屏（结构差异，docs/28 §3-T5.1
 * 注记，非窄差）。</p>
 */
public final class ExtensionComponentOverlay implements ScreenOverlay, ExtensionDialog {

    private final ExtensionComponent component;
    private final IntSupplier terminalCols;
    private final CompletableFuture<Object> result;
    /** handle.setHidden(true) 的摘态（摘时渲染零行——模态层仍占焦点位）。 */
    private volatile boolean hidden;
    private volatile boolean done;

    /**
     * @param component    扩展组件（工厂已调用）
     * @param terminalCols 终端列数源（render(width) 的宽）
     * @param result       结局 future（done 闭包直送；cancel/复位补 null）
     */
    public ExtensionComponentOverlay(ExtensionComponent component, IntSupplier terminalCols,
                                     CompletableFuture<Object> result) {
        this.component = component;
        this.terminalCols = terminalCols;
        this.result = result;
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        component.handleInput(TamboUIAdapter.toStroke(event));
        return true;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /** pi {@code close} 的关屏那一半（done 闭包已先完成 future——幂等闸在宿主）。 */
    public void markDone() {
        done = true;
    }

    /** {@inheritDoc}——apply 为空：结果由 done 闭包直送（docs/28 §3-T5.1 逐字）。 */
    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // 结果由 done 闭包直送（此处无操作）。
    }

    /** 宿主复位路径的取消（pi 无对应——done 未调时组件被摘，future 补 null）。 */
    @Override
    public void cancel() {
        done = true;
    }

    @Override
    public CompletableFuture<Object> resultFuture() {
        return result;
    }

    /** pi {@code OverlayHandle.setHidden} 对位（摘/回挂）。 */
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    /** pi {@code OverlayHandle.isHidden} 对位。 */
    public boolean isHidden() {
        return hidden;
    }

    /** 被挂的扩展组件（dispose 路径用）。 */
    public ExtensionComponent component() {
        return component;
    }

    @Override
    public Element render() {
        var children = new ArrayList<Element>();
        if (!hidden) {
            for (var line : component.render(Math.max(1, terminalCols.getAsInt()))) {
                // 组件经 host env 的 colorize 产出 TamboUI markup（[accent]…[/]）。
                children.add(TamboUIAdapter.markupText(line));
            }
        }
        if (children.isEmpty()) {
            children.add(TamboUIAdapter.spacer(1));
        }
        return TamboUIAdapter.column(children);
    }
}
