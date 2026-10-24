package com.pijava.tui.app;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.ConfigResourceWriteBack;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.SourceResolver;
import com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope;
import com.pijava.coding.agent.spi.ScopedResolvedPaths;
import com.pijava.tui.component.config.ConfigSelectorComponent;
import com.pijava.tui.component.config.ConfigSelectorModel;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;

/**
 * pi {@code selectConfig}（cli/config-selector.ts:20-61）对位（批 19 T6）：裸 TUI
 * 启动壳——主题/终端装配，全屏跑 {@link ConfigSelectorComponent} 直到 esc/ctrl+c
 * 关闭（pi 的 onClose ⇒ ui.stop()／onExit ⇒ process.exit(0)——java 统一
 * runner.quit() 交还终端，命令层返回 0）。
 */
public final class ConfigSelectorRunner {

    private ConfigSelectorRunner() {}

    /** 全屏跑资源配置选择器直到关闭；返回进程退出码（评审 I-1：无终端 ⇒ 1，
     *  与 PiTuiLauncher:31-35/SessionSelectorRunner 先例同向）。 */
    public static int select(ScopedResolvedPaths resolvedPaths, SettingsManager settings,
            Path cwd, Path agentDir, WriteScope writeScope, boolean projectModeAvailable) {
        if (System.console() == null && !Boolean.getBoolean("pi-java.dev.forceTui")) {
            // 窄差 D70 同族（会话选择器先例）。
            System.err.println("error: config requires a real terminal "
                + "(run from Windows Terminal / cmd / PowerShell)");
            return 1;
        }
        dev.tamboui.toolkit.app.ToolkitRunner runner;
        try {
            runner = TamboUIAdapter.createRunner(settings.uiPrefs().getShowHardwareCursor());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the config selector TUI", e);
        }
        // 批 21（T9）：主题初始化＝pi 序（settings 名〔对形经 resolveThemeSetting〕
        // ?? null ⇒ initTheme 内落 system）；选择器路径无注册主题表（pi 同——
        // 启动选择器只认内建＋<agentDir>/themes 目录扫描）。
        com.pijava.coding.agent.theme.Themes.initTheme(
            com.pijava.coding.agent.theme.ThemeSettings.resolveThemeSetting(
                settings.effective().theme,
                com.pijava.coding.agent.theme.Themes.terminalTheme()), false);
        runner.styleEngine(com.pijava.tui.theme.ThemeApplicator.engineForCurrentTheme());

        var model = new ConfigSelectorModel(
            ConfigSelectorModel.buildGroups(resolvedPaths.global(), agentDir),
            ConfigSelectorModel.buildGroups(resolvedPaths.project(), agentDir),
            writeScope);
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        var component = new ConfigSelectorComponent(model,
            new ConfigResourceWriteBack(settings, resolver, cwd, agentDir),
            24, projectModeAvailable);
        var cols = new AtomicInteger(80);
        runner.eventRouter().addGlobalHandler(event -> {
            if (event instanceof ResizeEvent resize) {
                cols.set(resize.width());
                component.setTerminalHeight(resize.height());
                return EventResult.HANDLED;
            }
            if (event instanceof KeyEvent keyEvent) {
                component.onKeyEvent(keyEvent);
                if (component.isDone()) {
                    runner.quit();
                }
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        });
        try {
            runner.run(() -> component.render(Math.max(1, cols.get())));
        } catch (Exception e) {
            throw new IllegalStateException("Config selector failed: " + e.getMessage(), e);
        }
        return 0;
    }
}
