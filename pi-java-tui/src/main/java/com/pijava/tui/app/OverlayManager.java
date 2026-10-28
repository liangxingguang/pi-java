package com.pijava.tui.app;

import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ModelSelectorScreen;
import com.pijava.tui.screen.ModelThinkingLevelScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.screen.SettingsScreen;
import com.pijava.tui.screen.ThemeSubmenuScreen;

/**
 * 批 29（E1 结构债拆分，PiTuiApp 簇⑧纯移动——行为零变化）：模态 overlay 的
 * 开合与结局收口（键路径与帧路径共用）。会话经 {@code app.session} 现读。
 */
final class OverlayManager {

    private final PiTuiApp app;
    private final ChatScreen chatScreen;
    private final SlashFlowRouter slashFlow;
    // Written on the render thread (dispatcher drain) and read by the global
    // key handler plus test hooks on other threads — needs volatile visibility.
    private volatile ScreenOverlay overlay;

    OverlayManager(PiTuiApp app, ChatScreen chatScreen, SlashFlowRouter slashFlow) {
        this.app = app;
        this.chatScreen = chatScreen;
        this.slashFlow = slashFlow;
    }

    /**
     * overlay 结局收口（键路径与帧路径共用——批 17 起倒计时/abort/会话复位在
     * 键路径外完成）。docs/18：先清位再 apply——apply 可链式开下一个 overlay
     * （树导航流：树选择器→三选→编辑器），旧序会把它立即抹掉。
     */
    void closeOverlayIfDone() {
        var done = overlay;
        if (done == null || !done.isDone()) {
            return;
        }
        overlay = null;
        done.apply(app.session, app::switchSession);
        // 批 12 T14：设置屏的 thinking-level 子菜单请求 ⇒ 不关屏改开
        // ModelThinkingLevelScreen（pi settings-selector.ts:607-610 对位）。
        if (done instanceof SettingsScreen settings
                && settings.pollModelThinkingRequest()) {
            openOverlay(new ModelThinkingLevelScreen(app.session));
            return;
        }
        // 批 21（T11，map/05 S9；pi settings-selector.ts:749-756 对位）：
        // Theme 子菜单请求 ⇒ 不关屏改开 ThemeSubmenuScreen。
        if (done instanceof SettingsScreen settings
                && settings.pollThemeRequest()) {
            openOverlay(new ThemeSubmenuScreen(app.session, app.themeController()));
            return;
        }
        // 批 13 T9（pi :5045-5048 的 rebuildChatFromMessages 对位）：
        // "Cache miss notices" 改动 ⇒ 整表重建（重推导 miss 通知/暖行）。
        if (done instanceof SettingsScreen settings
                && settings.pollCacheMissNoticesToggled()) {
            chatScreen.rebuildTranscript(app.session.accumulatedEntries(),
                app.session.harness().snapshot(app.session.laneName()).leafId());
        }
        // 批 12 T13（pi :4550 模型选择结果时机对位）。
        if (done instanceof ModelSelectorScreen) {
            app.maybeWarnSubscriptionAuth();
        }
        // B244（pi interactive-mode.ts:4986-4989 对位）：设置可能已改，
        // 关屏统一重建补全目录（pi 是 toggle 即时重建，窄差 D4 时机差；
        // 重建是纯读装配，无副作用）。
        chatScreen.setSlashCommands(slashFlow.loadSlashItems());
    }

    void openOverlay(ScreenOverlay screenOverlay) {
        this.overlay = screenOverlay;
    }

    /**
     * 批 17（T4/T5）：扩展对话框的程序式关闭（abort/timeout/会话复位路径——
     * pi {@code hideExtensionSelector} 族的「清位＋编辑器回槽」在 java 的对位：
     * 编辑器从未离屏（裁决④），只需清位；apply 照常完成结局 future）。
     */
    void dismissOverlay(ScreenOverlay target) {
        if (overlay != target) {
            return;
        }
        overlay = null;
        target.apply(app.session, app::switchSession);
    }

    /** The current modal overlay, or null (test hook). */
    ScreenOverlay currentOverlay() {
        return overlay;
    }
}
