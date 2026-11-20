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
    // docs/64（L7 W4，pi interactive-mode.ts:573 `extensionDialogs` 栈）：写于 dispatcher
    // 线程、读于全局键处理/测试钩子 ⇒ 全部入口 synchronized。栈顶＝head。
    private final java.util.Deque<ScreenOverlay> stack = new java.util.ArrayDeque<>();

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
    synchronized void closeOverlayIfDone() {
        var done = stack.peek();
        if (done == null || !done.isDone()) {
            return;
        }
        stack.pop();
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
        // 设置屏在每次切换时已即时重建；其它 overlay 关屏仍走旧统一时机。
        if (!(done instanceof SettingsScreen)) {
            chatScreen.setSlashCommands(slashFlow.loadSlashItems());
        }
    }

    synchronized void openOverlay(ScreenOverlay screenOverlay) {
        if (screenOverlay != null) {
            stack.push(screenOverlay);
        }
    }

    /**
     * 批 17（T4/T5）：扩展对话框的程序式关闭（abort/timeout/会话复位路径——
     * pi {@code hideExtensionSelector} 族的「清位＋编辑器回槽」在 java 的对位：
     * 编辑器从未离屏（裁决④），只需清位；apply 照常完成结局 future）。
     */
    synchronized void dismissOverlay(ScreenOverlay target) {
        // 按引用移除（pi removeExtensionDialog :2799-2804）：非栈顶被隐藏时不能误伤栈顶。
        if (!stack.removeIf(o -> o == target)) {
            return;
        }
        target.apply(app.session, app::switchSession);
    }

    /** The current (topmost) modal overlay, or null (test hook). */
    synchronized ScreenOverlay currentOverlay() {
        return stack.peek();
    }

    /** 栈深（测试探针；0 ⇒ 回落编辑器）。 */
    synchronized int depth() {
        return stack.size();
    }
}
