package com.pijava.tui.app;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.tui.screen.FirstTimeSetupScreen;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;

/**
 * 首次设置向导启动壳（L7 W7，S18）：pi {@code cli/startup-ui.ts:186-231
 * showFirstTimeSetup} 的 java 对位。{@link TrustPromptRunner} 同型的裸 TUI
 * （主 UI 未起前全屏跑），提交后写 theme/analytics 并 flush；取消不写。
 */
public final class FirstTimeSetupRunner {

    private FirstTimeSetupRunner() {}

    /** 条件满足时弹向导；任何异常都不阻塞启动。 */
    public static void runIfNeeded() {
        if (!FirstRunSetup.shouldRun()) {
            return;
        }
        try {
            run();
        } catch (Exception ignored) {
            // 向导失败不阻塞启动（pi finish(undefined) 臂等价）。
        }
    }

    static void run() throws Exception {
        if (System.console() == null && !Boolean.getBoolean("pi-java.dev.forceTui")) {
            return;
        }
        var screen = new FirstTimeSetupScreen();
        var done = new AtomicBoolean();
        var cols = new AtomicInteger(80);
        // 启动预览：系统主题（pi setTheme(SYSTEM_THEME_NAME)）。
        com.pijava.coding.agent.theme.Themes.initTheme("system", false);
        var runner = TamboUIAdapter.createRunner(false);
        runner.styleEngine(com.pijava.tui.theme.ThemeApplicator.engineForCurrentTheme());
        runner.eventRouter().addGlobalHandler(event -> {
            if (event instanceof ResizeEvent resize) {
                cols.set(resize.width());
                return EventResult.HANDLED;
            }
            if (!(event instanceof KeyEvent key)) {
                return EventResult.UNHANDLED;
            }
            if (key.isUp() || key.isChar('k')) {
                screen.move(-1, FirstTimeSetupRunner::preview);
            } else if (key.isDown() || key.isChar('j')) {
                screen.move(1, FirstTimeSetupRunner::preview);
            } else if (key.isConfirm()) {
                if (screen.confirm()) {
                    persist(screen.result());
                    done.set(true);
                }
            } else if (key.isCancel()) {
                screen.cancel();
                done.set(true);
            }
            if (done.get()) {
                runner.quit();
            }
            return EventResult.HANDLED;
        });
        runner.run(() -> TamboUIAdapter.column(
            TamboUIAdapter.text(String.join("\n", screen.lines(cols.get()))),
            TamboUIAdapter.spacer(),
            TamboUIAdapter.text(screen.hint())));
    }

    /** 主题预览：换主题并重建样式表（pi onThemePreview）。 */
    private static void preview(String themeName) {
        try {
            com.pijava.coding.agent.theme.Themes.initTheme(themeName, false);
        } catch (Exception ignored) {
            // 预览失败保留当前主题（不阻塞向导）。
        }
    }

    /** 提交落盘（pi setTheme＋setEnableAnalytics＋flush）。 */
    private static void persist(FirstTimeSetupScreen.Result result) {
        var settings = SettingsManager.load(null);
        settings.accessors().setTheme(result.theme());
        settings.install().setEnableAnalytics(result.shareAnalytics());
        settings.flush();
    }
}
