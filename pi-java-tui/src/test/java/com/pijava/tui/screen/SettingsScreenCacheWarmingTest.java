package com.pijava.tui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 批 13（docs/24 §7，T9）：设置页两行——"Cache warming"（pi settings-selector.ts
 * :521-527 三值循环；setter 走 {@code session.setCacheWarmingMode} 含
 * onModeChanged，pi :5005-5008）与 "Cache miss notices"（:543-548 布尔；改动后
 * rebuild——检出模式随批 12 T14）。
 */
class SettingsScreenCacheWarmingTest {

    /** "Cache warming" 的行号（"Warn Anthropic extra usage" 之后、子菜单行之前）。 */
    private static final int CACHE_WARMING_ROW = 16;
    private static final int CACHE_MISS_NOTICES_ROW = 17;

    private static void down(SettingsScreen screen, int times) {
        for (int i = 0; i < times; i++) {
            screen.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        }
    }

    /** user.home 指向临时目录（SettingsScreenTest 同款——flush 不碰真实 settings）。 */
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
            try (var session = AgentSession.create(ArgsParser.parse(new String[] {}))) {
                var screen = new SettingsScreen(session);
                down(screen, CACHE_WARMING_ROW);

                // 默认 "streaming" ⇒ 一拍 "idle"（经 session.setCacheWarmingMode——
                // 含 onModeChanged 重调和，不走裸 accessor）。
                screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
                assertThat(session.services().settings().network().getCacheWarmingMode())
                    .isEqualTo("idle");

                screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
                assertThat(session.services().settings().network().getCacheWarmingMode())
                    .isEqualTo("off");
                assertThat(session.cacheWarmingStatus().reason())
                    .isEqualTo("cache warming disabled");

                screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
                assertThat(session.services().settings().network().getCacheWarmingMode())
                    .isEqualTo("streaming");
            }
        });
    }

    @Test
    void cacheMissNoticesRowTogglesAndFlagsRebuild() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] {}))) {
                var screen = new SettingsScreen(session);
                down(screen, CACHE_MISS_NOTICES_ROW);

                assertThat(session.services().settings().uiPrefs().getShowCacheMissNotices())
                    .isFalse();
                assertThat(screen.pollCacheMissNoticesToggled()).isFalse();

                screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
                assertThat(session.services().settings().uiPrefs().getShowCacheMissNotices())
                    .isTrue();
                // pi :5045-5048 的 rebuild 触发——一次性检出（PiTuiApp 关屏处理点消费）。
                assertThat(screen.pollCacheMissNoticesToggled()).isTrue();
                assertThat(screen.pollCacheMissNoticesToggled()).isFalse();
            }
        });
    }
}
