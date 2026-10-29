package com.pijava.tui.screen;

import java.nio.file.Files;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Settings selector interaction regressions. */
class SettingsScreenTest {

    @Test
    void themeRowOpensSubmenuInsteadOfCycling() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] { }))) {
                var screen = new SettingsScreen(session);

                screen.activate("theme");

                assertThat(screen.pollThemeRequest()).isTrue();
                assertThat(session.services().settings().effective().theme).isNull();
                assertThat(screen.isDone()).isFalse();

                screen.escape();       // Theme 子菜单 ⇒ 设置主列表
                assertThat(screen.isDone()).isFalse();
                screen.escape();       // 设置主列表 ⇒ 关闭
                assertThat(screen.isDone()).isTrue();
            }
        });
    }

    @Test
    void followUpModeCyclesFromDefaultToAll() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] { }))) {
                var screen = new SettingsScreen(session);

                screen.activate("follow-up-mode");

                assertThat(session.services().settings().effective().followUpMode).isEqualTo("all");
            }
        });
    }

    private static final dev.tamboui.tui.event.KeyEvent ENTER =
        KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER);

    @Test
    void outputPadRowCyclesAndFlushes() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] { }))) {
                var screen = new SettingsScreen(session);

                screen.activate("output-padding");

                assertThat(session.services().settings().uiPrefs().getOutputPad()).isZero();
            }
        });
    }

    @Test
    void warningsRowTogglesAnthropicExtraUsage() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] { }))) {
                var screen = new SettingsScreen(session);

                screen.activate("warnings");
                screen.onKeyEvent(ENTER);

                assertThat(session.services().settings().uiPrefs()
                    .getWarnings().anthropicExtraUsage()).isFalse();
            }
        });
    }

    @Test
    void thinkingLevelRowRequestsTheSubmenu() throws Exception {
        withIsolatedHome(() -> {
            try (var session = AgentSession.create(ArgsParser.parse(new String[] { }))) {
                var screen = new SettingsScreen(session);

                screen.activate("model-thinking");

                assertThat(screen.pollModelThinkingRequest()).isTrue();
                assertThat(screen.pollModelThinkingRequest()).isFalse();
                assertThat(screen.isDone()).isFalse();
                assertThat(session.services().settings().effective().modelThinkingLevels).isNull();
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
