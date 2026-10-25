package com.pijava.tui.screen;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.theme.ThemeController;
import com.pijava.coding.agent.theme.Themes;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThemeSubmenuScreen} 测试（pi settings-selector.ts ThemeSubmenu 对位，
 * 批 21/docs/32 §7；map/05 S9）。
 */
class ThemeSubmenuScreenTest {

    @AfterEach
    void reset() {
        Themes.resetForTests();
    }

    @Test
    void singleModeEnterPersistsTheme() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] {}))) {
                var controller = newController(session, new AtomicInteger());
                var screen = new ThemeSubmenuScreen(session, controller);
                // 无设置 ⇒ 默认选中 system 且首项即 system（pi singleModeThemeItems 置顶）。
                press(screen, KeyCode.ENTER);
                assertThat(screen.isDone()).isTrue();
                assertThat(session.services().settings().effective().theme).isEqualTo("system");
            }
        });
    }

    @Test
    void automaticFlowPersistsPair() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] {}))) {
                var previewCount = new AtomicInteger();
                var controller = newController(session, previewCount);
                var screen = new ThemeSubmenuScreen(session, controller);
                // 下到 automatic 项（system 之后第二项）并进入自动模式。
                press(screen, KeyCode.DOWN);
                press(screen, KeyCode.ENTER);
                assertThat(screen.isDone()).isFalse();   // 进入 automatic 菜单不关闭
                // 默认两半对＝当前主题（pi defaultAutomaticThemes）⇒ Apply 持久对形。
                press(screen, KeyCode.DOWN);   // → Dark theme 行
                press(screen, KeyCode.DOWN);   // → Apply 行
                press(screen, KeyCode.ENTER);
                assertThat(screen.isDone()).isTrue();
                assertThat(session.services().settings().effective().theme)
                    .isEqualTo("system/system");
                // 进自动模式时预览过一次对形（pi :307-312）。
                assertThat(previewCount.get()).isGreaterThanOrEqualTo(1);
            }
        });
    }

    @Test
    void escapeRestoresOriginalSetting() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] {}))) {
                var previewCount = new AtomicInteger();
                var controller = newController(session, previewCount);
                var screen = new ThemeSubmenuScreen(session, controller);
                // 改动高亮（触发预览）后 Esc ⇒ 预览还原原设置＋关闭＋不持久。
                press(screen, KeyCode.DOWN);
                press(screen, KeyCode.ESCAPE);
                assertThat(screen.isDone()).isTrue();
                assertThat(session.services().settings().effective().theme).isNull();
                // 最后一次预览是原设置（"system" 默认——原设置 null ⇒ activeName）。
                assertThat(Themes.current().name()).isEqualTo("system");
            }
        });
    }

    private static ThemeController newController(AgentSession session, AtomicInteger counter) {
        return new ThemeController(
            session.services()::settings,
            message -> { },
            counter::incrementAndGet,
            null);
    }

    private static void press(ScreenOverlay screen, KeyCode code) {
        screen.onKeyEvent(KeyEvent.ofKey(code));
    }

    /** Point user.home at a temp dir so flush() never touches real settings. */
    private static void withIsolatedHome(IoRunnable action) throws Exception {
        var tempHome = Files.createTempDirectory("pi-java-theme-submenu-test");
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
}
