package com.pijava.coding.agent.spi;

import java.nio.file.Path;

import com.pijava.coding.agent.core.SettingsManager;

/**
 * {@code pi-java config} 资源开关 TUI 的 SPI（批 19 T6，docs/30 §3）——pi
 * {@code selectConfig}（cli/config-selector.ts:20-61）对位。
 *
 * <p>TUI 模块依赖 coding-agent（反向依赖不可），故选择器经本 SPI 由
 * pi-java-tui 注册（{@code META-INF/services/...ConfigSelectorProvider}）。
 * SPI 缺席（裁 tui 的精简部署）时调用方报干净错误（窄差 D70 同族先例）。</p>
 */
public interface ConfigSelectorProvider {

    /** pi config 写作用域字面量闭集（{@code "global" | "project"}）⇒ enum。 */
    enum WriteScope {
        GLOBAL, PROJECT;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 全屏跑资源配置 TUI 直到关闭（pi {@code selectConfig} 的 Promise 对位）。
     *
     * @param resolvedPaths        双份 resolve（global/project）
     * @param settings             设置（写回＋主题/键位来源）
     * @param cwd                  项目目录
     * @param agentDir             全局 agent 目录
     * @param writeScope           初始写作用域（{@code config -l} ⇒ PROJECT）
     * @param projectModeAvailable 未信任 ⇒ false（tab 切换门关闭）
     */
    void selectConfig(ScopedResolvedPaths resolvedPaths, SettingsManager settings,
                      Path cwd, Path agentDir, WriteScope writeScope,
                      boolean projectModeAvailable);
}
