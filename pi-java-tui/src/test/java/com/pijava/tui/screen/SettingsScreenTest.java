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
