package com.pijava.tui.app;

import java.util.Optional;

import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.spi.SessionListLoader;
import com.pijava.coding.agent.spi.SessionSelectorProvider;

/**
 * {@link SessionSelectorProvider} 的 tui 侧实现（批 14，docs/25 T11a，裁决①）——
 * 经 {@code META-INF/services} 注册，转调 {@link SessionSelectorRunner}。
 */
public final class SessionSelectorProviderImpl implements SessionSelectorProvider {

    @Override
    public Optional<String> selectSession(SessionListLoader currentScope,
                                          SessionListLoader allScope,
                                          SettingsManager settings, String useThemeOverride) {
        return SessionSelectorRunner.select(currentScope, allScope, settings, useThemeOverride);
    }
}
