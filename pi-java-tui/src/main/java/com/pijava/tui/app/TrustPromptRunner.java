package com.pijava.tui.app;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.ProjectTrustOption;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;

/**
 * 批 23（docs/34 T3）：启动信任弹窗——pi {@code selectProjectTrustOption}
 * （project-trust.ts:36-44，经 startup selector）对位。裸 TUI 启动壳
 * （批 14 {@link SessionSelectorRunner} 同 niche）：主 UI 未起前全屏跑一个
 * 选项列表（↑/↓＋enter 选定、esc 取消 ⇒ {@code null}）。
 *
 * <p>提示文案逐字（pi project-trust.ts:29-33 {@code formatProjectTrustPrompt}，
 * APP_NAME＝pi-java、配置目录名＝.pi-java）。</p>
 */
public final class TrustPromptRunner {

    private TrustPromptRunner() {}

    /** pi {@code formatProjectTrustPrompt}（project-trust.ts:29-33 逐字）。 */
    static String promptText(Path cwd) {
        return "Trust project folder?\n" + cwd + "\n\n"
            + "This allows pi-java to load .pi-java settings and resources,"
            + " install missing project packages, and execute project extensions.";
    }

    /**
     * 跑信任弹窗。
     *
     * @param cwd     项目目录
     * @param options pi 五选项（{@link ProjectTrustOption#of}）
     * @return 选中项；取消/无终端 ⇒ {@code null}（pi select 返 undefined 臂）
     */
    public static ProjectTrustOption prompt(Path cwd, List<ProjectTrustOption> options) {
        // 与 PiTuiLauncher:31-35 同判（无 PTY ⇒ 取消臂；resolver 侧即 false）。
        if (System.console() == null && !Boolean.getBoolean("pi-java.dev.forceTui")) {
            return null;
        }
        // 主题/硬件光标读全局设置（预信任——pi startup selector 同读 startup settings）。
        var settings = SettingsManager.load(null);
        dev.tamboui.toolkit.app.ToolkitRunner runner;
        try {
            runner = TamboUIAdapter.createRunner(settings.uiPrefs().getShowHardwareCursor());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the project-trust prompt TUI", e);
        }
        com.pijava.coding.agent.theme.Themes.initTheme(
            com.pijava.coding.agent.theme.ThemeSettings.resolveThemeSetting(
                null, com.pijava.coding.agent.theme.Themes.terminalTheme()), false);
        runner.styleEngine(com.pijava.tui.theme.ThemeApplicator.engineForCurrentTheme());

        var labels = options.stream().map(ProjectTrustOption::label).toList();
        var selected = new AtomicInteger(0);
        var done = new boolean[] {false};
        var cancelled = new boolean[] {false};
        var cols = new AtomicInteger(80);
        runner.eventRouter().addGlobalHandler(event -> {
            if (event instanceof ResizeEvent resize) {
                cols.set(resize.width());
                return EventResult.HANDLED;
            }
            if (event instanceof KeyEvent keyEvent) {
                if (keyEvent.isUp()) {
                    selected.set(Math.max(0, selected.get() - 1));
                } else if (keyEvent.isDown()) {
                    selected.set(Math.min(labels.size() - 1, selected.get() + 1));
                } else if (keyEvent.isConfirm()) {
                    done[0] = true;
                } else if (keyEvent.isCancel()) {
                    cancelled[0] = true;
                    done[0] = true;
                }
                if (done[0]) {
                    runner.quit();
                }
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        });
        try {
            runner.run(() -> TamboUIAdapter.column(
                TamboUIAdapter.text(promptText(cwd)),
                TamboUIAdapter.spacer(),
                TamboUIAdapter.list(labels)
                    .selected(selected.get())
                    .highlightColor(dev.tamboui.style.Color.CYAN)
                    .rounded()
                    .fill()));
        } catch (Exception e) {
            throw new IllegalStateException("Project-trust prompt failed: " + e.getMessage(), e);
        }
        return cancelled[0] ? null : options.get(selected.get());
    }
}
