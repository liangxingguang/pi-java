package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;

import com.pijava.coding.agent.cli.Args;

/**
 * L5 W5 拆件（docs/04 结构债惯例）：{@link ExtensionLoading} 的「主题候选与
 * reload 收尾」簇——themePaths 基线／resources_discover 合并／{@code reloadThemes}
 * 装载／reload 监听者。自 {@code ExtensionLoading} 纯移动（行为零变化），只换
 * 承载者以守住文件 ≤500 行闸门。
 */
final class ExtensionThemes {

    private final AgentSession owner;

    // resources_discover：扩展贡献的主题候选（启动时选第一个可用）。
    private List<Path> themePaths = List.of();
    /** 批 21（T8）：themePaths 的装载结果（pi resourceLoader.getThemes() 对位）。 */
    private List<com.pijava.coding.agent.theme.Theme> themes = List.of();
    /** 批 21（T8）：reload 完成监听者（模式层主题收尾口）。 */
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> reloadListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    ExtensionThemes(AgentSession owner) {
        this.owner = owner;
    }

    /** 扩展贡献的主题候选（resources_discover themePaths）；无则空列表。 */
    List<Path> themePaths() {
        return themePaths;
    }

    /** 主题候选写面（loadExtensions 基线／resources_discover 合并臂）。 */
    void themePaths(List<Path> paths) {
        this.themePaths = paths;
    }

    /**
     * 批 21（T8）：已装载的主题表（pi {@code resourceLoader.getThemes().themes}
     * 对位——装配期与 /reload 经 {@link #reloadThemes()} 重算）。
     */
    List<com.pijava.coding.agent.theme.Theme> themes() {
        return themes;
    }

    /** 批 21（T8）：{@link SessionReload} ⑤ 的写面——重设 themePaths 并重装载。 */
    void setThemePathsForReload(List<String> paths) {
        themePaths = paths.stream().map(Path::of).toList();
        reloadThemes();
    }

    /**
     * 批 21（T8，裁决⑩）：reload 完成监听——pi 的 setRegisteredThemes＋
     * {@code themeController.applyFromSettings()} 在 InteractiveMode 收尾
     * （interactive-mode.ts:6490-6492）；java 的 /reload 封在 slash 命令里，
     * 模式层经此口接收尾（注册表写入/控制器应用都在监听者侧，会话层保持
     * toolkit 中立）。
     */
    void addReloadListener(Runnable listener) {
        reloadListeners.addIfAbsent(listener);
    }

    /** {@link SessionReload} 收尾回调。 */
    void fireReloaded() {
        for (var listener : reloadListeners) {
            listener.run();
        }
    }

    /**
     * 批 21（T8）：装载 themePaths 指向的主题文件（pi
     * {@code updateThemesFromPaths} 对位——resolve 的自动发现条目已含
     * {@code <agentDir>/themes} 与项目 {@code .pi-java/themes}）。装载/碰撞诊断
     * 进 {@code startupDiagnostics}（pi 的 themeDiagnostics 对位；模式层随
     * startupDiagnostics 既有通道上报）。
     */
    void reloadThemes() {
        var result = com.pijava.coding.agent.theme.ThemeLoader.loadThemes(
            themePaths.stream().map(Path::toString).toList(),
            com.pijava.coding.agent.theme.Themes.colorMode());
        themes = List.copyOf(result.themes());
        for (var diagnostic : result.diagnostics()) {
            owner.extensions().startupDiagnosticsInternal().add(new Args.ArgDiagnostic(
                "collision".equals(diagnostic.level()) ? "collision" : diagnostic.level(),
                diagnostic.filePath() != null
                    ? diagnostic.filePath() + ": " + diagnostic.message()
                    : diagnostic.message()));
        }
    }
}
