package com.pijava.tui.screen;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import dev.tamboui.tui.event.KeyEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 review fixes: the settings page navigates and cycles field values.
 */
class SettingsScreenTest {

    @Test
    void cyclesThemeOnEnterAndFlushes() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(
                    ArgsParser.parse(new String[] {}))) {
                var screen = new SettingsScreen(session);

                // First field is Theme; unset → dark → light.
                screen.onKeyEvent(KeyEvent.ofKey(
                    dev.tamboui.tui.event.KeyCode.ENTER));
                screen.onKeyEvent(KeyEvent.ofKey(
                    dev.tamboui.tui.event.KeyCode.ENTER));

                assertThat(session.services().settings().effective().theme)
                    .isEqualTo("light");
                assertThat(screen.isDone()).isFalse();

                screen.onKeyEvent(KeyEvent.ofKey(
                    dev.tamboui.tui.event.KeyCode.ESCAPE));
                assertThat(screen.isDone()).isTrue();
            }
        });
    }

    @Test
    void downAndEnterCycleFollowUpMode() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(
                    ArgsParser.parse(new String[] {}))) {
                var screen = new SettingsScreen(session);

                screen.onKeyEvent(KeyEvent.ofKey(
                    dev.tamboui.tui.event.KeyCode.DOWN));
                screen.onKeyEvent(KeyEvent.ofKey(
                    dev.tamboui.tui.event.KeyCode.DOWN));
                screen.onKeyEvent(KeyEvent.ofKey(
                    dev.tamboui.tui.event.KeyCode.ENTER));
                screen.onKeyEvent(KeyEvent.ofKey(
                    dev.tamboui.tui.event.KeyCode.ENTER));

                assertThat(session.services().settings().effective().followUpMode)
                    .isEqualTo("all");
            }
        });
    }

    // ── 批 12（docs/23 §3/§7）：消费键行＋T14 子菜单入口 ─────────

    private static final dev.tamboui.tui.event.KeyEvent ENTER =
        KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER);
    private static final dev.tamboui.tui.event.KeyEvent DOWN =
        KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN);

    @Test
    void outputPadRowCyclesAndFlushes() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(
                    ArgsParser.parse(new String[] {}))) {
                var screen = new SettingsScreen(session);
                // Output pad 行（第 11 行，0 起）：默认 1 ⇒ 循环到 0。
                for (int i = 0; i < 11; i++) {
                    screen.onKeyEvent(DOWN);
                }
                screen.onKeyEvent(ENTER);

                assertThat(session.services().settings().uiPrefs().getOutputPad()).isZero();
            }
        });
    }

    @Test
    void warningsRowTogglesAnthropicExtraUsage() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(
                    ArgsParser.parse(new String[] {}))) {
                var screen = new SettingsScreen(session);
                // Warn Anthropic extra usage 行（第 15 行）：默认 true ⇒ 循环到 false。
                for (int i = 0; i < 15; i++) {
                    screen.onKeyEvent(DOWN);
                }
                screen.onKeyEvent(ENTER);

                assertThat(session.services().settings().uiPrefs()
                    .getWarnings().anthropicExtraUsage()).isFalse();
            }
        });
    }

    @Test
    void thinkingLevelRowRequestsTheSubmenu() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(
                    ArgsParser.parse(new String[] {}))) {
                var screen = new SettingsScreen(session);
                // 末行（第 16 行）＝子菜单入口：Enter 置请求位、不写设置、不关屏。
                for (int i = 0; i < 16; i++) {
                    screen.onKeyEvent(DOWN);
                }
                screen.onKeyEvent(ENTER);

                assertThat(screen.pollModelThinkingRequest()).isTrue();
                // 一次性检出：再 poll 复位。
                assertThat(screen.pollModelThinkingRequest()).isFalse();
                assertThat(screen.isDone()).isFalse();
                assertThat(session.services().settings().effective().modelThinkingLevels)
                    .isNull();
            }
        });
    }

    /** Point user.home at a temp dir so flush() never touches real settings. */
    private static void withIsolatedHome(Runnable action) throws Exception {
        var tempHome = Files.createTempDirectory("pi-java-settings-test");
        var originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            action.run();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
