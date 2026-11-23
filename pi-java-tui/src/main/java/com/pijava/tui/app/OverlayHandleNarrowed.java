package com.pijava.tui.app;

import java.util.function.Consumer;

import com.pijava.coding.agent.extension.ExtensionOverlayHandle;
import com.pijava.tui.screen.ExtensionComponentOverlay;
import com.pijava.tui.screen.ScreenOverlay;

/**
 * pi {@code OverlayHandle} 的窄化三件（docs/28 §4/窄差 D99）：hide 摘屏、
 * setHidden 经调度器在渲染线程生效、isHidden 直读组件。
 */
final class OverlayHandleNarrowed implements ExtensionOverlayHandle {

    private final ExtensionComponentOverlay overlay;
    private final Consumer<ScreenOverlay> dismiss;
    private final Consumer<Runnable> dispatch;

    OverlayHandleNarrowed(ExtensionComponentOverlay overlay,
                          Consumer<ScreenOverlay> dismiss, Consumer<Runnable> dispatch) {
        this.overlay = overlay;
        this.dismiss = dismiss;
        this.dispatch = dispatch;
    }

    @Override
    public void hide() {
        dismiss.accept(overlay);   // 摘屏；结局仍由 done 闭包决定（pi 同构）
    }

    @Override
    public void setHidden(boolean hidden) {
        dispatch.accept(() -> overlay.setHidden(hidden));
    }

    @Override
    public boolean isHidden() {
        return overlay.isHidden();
    }
}
