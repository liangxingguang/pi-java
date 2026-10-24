package com.pijava.tui.app;

import java.nio.file.Path;

import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.spi.ConfigSelectorProvider;
import com.pijava.coding.agent.spi.ScopedResolvedPaths;

/**
 * {@link ConfigSelectorProvider} 的 tui 实现（批 19 T6）——{@code META-INF/services}
 * 注册，coding-agent 的 {@code config} 子命令经 SPI 调入。
 */
public final class ConfigSelectorProviderImpl implements ConfigSelectorProvider {

    @Override
    public int selectConfig(ScopedResolvedPaths resolvedPaths, SettingsManager settings,
            Path cwd, Path agentDir, WriteScope writeScope, boolean projectModeAvailable) {
        return ConfigSelectorRunner.select(resolvedPaths, settings, cwd, agentDir, writeScope,
            projectModeAvailable);
    }
}
