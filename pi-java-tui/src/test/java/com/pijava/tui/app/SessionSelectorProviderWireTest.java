package com.pijava.tui.app;

import java.util.List;
import java.util.ServiceLoader;

import com.pijava.coding.agent.core.SessionResolutionExit;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.spi.SessionSelectorProvider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 批 14（docs/25 T11，§7 #9）：SPI 发现（tui 在 classpath）＋接线到达 runner
 * （无头环境抛干净终端错误——选择器键面 e2e 由 SessionSelectorComponentTest 覆盖）。
 */
class SessionSelectorProviderWireTest {

    @Test
    void spiDiscoversTheTuiImplementation() {
        var provider = ServiceLoader.load(SessionSelectorProvider.class).findFirst();

        assertThat(provider).isPresent();
        assertThat(provider.get()).isInstanceOf(SessionSelectorProviderImpl.class);
    }

    @Test
    void headlessSelectorFailsWithTheCleanTerminalError() {
        var provider = ServiceLoader.load(SessionSelectorProvider.class).findFirst().orElseThrow();

        var thrown = catchThrowable(() -> provider.selectSession(
            (progress, signal) -> List.of(), (progress, signal) -> List.of(),
            SettingsManager.load(null), null));

        assertThat(thrown).isInstanceOf(SessionResolutionExit.class);
        var exit = (SessionResolutionExit) thrown;
        assertThat(exit.code()).isEqualTo(1);
        assertThat(exit.errorPrefix()).isTrue();
        assertThat(exit.getMessage()).contains("--resume requires a real terminal");
    }
}
