package com.pijava.tui.screen;

import java.nio.file.Files;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cache-warming mode and cache-miss notice settings regressions. */
class SettingsScreenCacheWarmingTest {

    private static void withIsolatedHome(IoRunnable action) throws Exception {
        var tempHome = Files.createTempDirectory("pi-java-settings-warm-test");
        var originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            action.run();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws Exception;
    }

    @Test
    void cacheWarmingRowCyclesThroughSessionEntryPoint() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] { }))) {
                var screen = new SettingsScreen(session);

                screen.activate("cache-warming-mode");
                assertThat(session.services().settings().network().getCacheWarmingMode())
                    .isEqualTo("idle");

                screen.activate("cache-warming-mode");
                assertThat(session.services().settings().network().getCacheWarmingMode())
                    .isEqualTo("off");
                assertThat(session.cacheWarmingStatus().reason())
                    .isEqualTo("cache warming disabled");

                screen.activate("cache-warming-mode");
                assertThat(session.services().settings().network().getCacheWarmingMode())
                    .isEqualTo("streaming");
            }
        });
    }

    @Test
    void cacheMissNoticesRowTogglesAndFlagsRebuild() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] { }))) {
                var screen = new SettingsScreen(session);

                assertThat(session.services().settings().uiPrefs().getShowCacheMissNotices())
                    .isFalse();
                assertThat(screen.pollCacheMissNoticesToggled()).isFalse();

                screen.activate("cache-miss-notices");
                assertThat(session.services().settings().uiPrefs().getShowCacheMissNotices())
                    .isTrue();
                assertThat(screen.pollCacheMissNoticesToggled()).isTrue();
                assertThat(screen.pollCacheMissNoticesToggled()).isFalse();
            }
        });
    }
}
