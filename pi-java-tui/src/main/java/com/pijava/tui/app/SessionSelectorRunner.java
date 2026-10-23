package com.pijava.tui.app;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.SessionResolutionExit;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.spi.SessionListLoader;
import com.pijava.tui.component.session.SessionSelectorComponent;
import com.pijava.tui.theme.PiTheme;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;

/**
 * pi {@code selectSession}（cli/session-picker.ts:15-55）＋
 * {@code createStartupTui}/{@code startStartupTui}（startup-ui.ts:85-95）对位
 * （批 14，docs/25 T11）：裸 TUI 启动壳——主题/键位/终端能力装配，全屏跑
 * {@link SessionSelectorComponent}，定案/取消 ⇒ {@code runner.quit()} 交还终端
 * （主 UI 随后才起）。
 */
public final class SessionSelectorRunner {

    private SessionSelectorRunner() {}

    /**
     * 全屏跑会话选择器。
     *
     * @param current          项目内会话加载器
     * @param all              跨项目会话加载器
     * @param settings         设置（主题/硬件光标来源）
     * @param useThemeOverride {@code --use-theme} 覆盖（pi :681-683 先覆盖 settings
     *                         再进选择器的对位；{@code null} ≙ 未给）
     * @return 选中会话 path；取消 ⇒ {@code empty}
     */
    public static Optional<String> select(SessionListLoader current, SessionListLoader all,
                                          SettingsManager settings, String useThemeOverride) {
        // 终端前置：与 PiTuiLauncher:31-35 同判（窄差 D70 同族）。
        if (System.console() == null && !Boolean.getBoolean("pi-java.dev.forceTui")) {
            throw SessionResolutionExit.error(
                "--resume requires a real terminal (run from Windows Terminal / cmd / PowerShell)");
        }
        dev.tamboui.toolkit.app.ToolkitRunner runner;
        try {
            runner = TamboUIAdapter.createRunner(settings.uiPrefs().getShowHardwareCursor());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the session selector TUI", e);
        }
        // pi :681-683：--use-theme 先覆盖再进选择器；picker 无扩展 themePaths（List.of()）。
        runner.styleEngine(PiTheme.engineFor(
            PiTheme.resolveTheme(useThemeOverride, settings.effective().theme, List.of())));

        var dispatcher = new TuiEventDispatcher();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var component = new SessionSelectorComponent(current, all, new KeybindingsManager(),
            executor, dispatcher::dispatch, System::currentTimeMillis, null);
        var cols = new AtomicInteger(80);   // ResizeEvent 跟踪（批 9 裁决⑧同手法，初值 80）
        runner.eventRouter().addGlobalHandler(event -> {
            if (event instanceof ResizeEvent resize) {
                cols.set(resize.width());
                return EventResult.HANDLED;
            }
            if (event instanceof KeyEvent keyEvent) {
                component.onKeyEvent(keyEvent);
                if (component.isDone()) {
                    runner.quit();   // pi :93-96：定案/取消 ⇒ ui.stop()
                }
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        });
        try {
            runner.run(() -> {
                dispatcher.drain();
                component.onTick();
                return component.render(Math.max(1, cols.get()));
            });
        } catch (SessionResolutionExit | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Session selector failed: " + e.getMessage(), e);
        } finally {
            executor.close();
        }
        return component.selectedPath();
    }
}
