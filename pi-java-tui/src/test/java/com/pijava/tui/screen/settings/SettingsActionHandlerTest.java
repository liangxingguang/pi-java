package com.pijava.tui.screen.settings;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SettingsActionHandlerTest {

    @Test
    void everyCycleRowHasARealWriteAction() throws Exception {
        try (var rig = new Rig()) {
            var items = SettingsItems.build(rig.session.services().settings(), ModelCatalog.empty(), null,
                "system", java.util.List.of("system"), true, "Alt+Enter", "Shift+Tab");
            for (var item : items) {
                if (item.submenu() != SettingsItem.Submenu.NONE) {
                    continue;
                }
                var next = item.values().get((item.values().indexOf(item.currentValue()) + 1)
                    % item.values().size());
                assertThatCode(() -> rig.handler.apply(item.id(), next))
                    .as(item.id()).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void specialValuesUseTheirPersistedRepresentation() throws Exception {
        try (var rig = new Rig()) {
            var settings = rig.session.services().settings();
            rig.handler.apply("quiet-startup", "header");
            rig.handler.apply("default-project-trust", "Never trust");
            rig.handler.apply("http-idle-timeout", "disabled");
            rig.handler.apply("fullscreen-wheel-scroll-lines", "5");

            assertThat(settings.accessors().getQuietStartupMode()).isEqualTo("header");
            assertThat(settings.accessors().getDefaultProjectTrust()).isEqualTo("never");
            assertThat(settings.network().getHttpIdleTimeoutMs()).isZero();
            assertThat(settings.uiPrefs().getFullscreenWheelScrollLines()).isEqualTo(5);
        }
    }

    @Test
    void unknownRowFailsInsteadOfSilentlyDroppingTheEdit() throws Exception {
        try (var rig = new Rig()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> rig.handler.apply("not-a-setting", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown setting: not-a-setting");
        }
    }

    private static final class Rig implements AutoCloseable {
        private final String oldHome = System.getProperty("user.home");
        private final AgentSession session;
        private final SettingsActionHandler handler;

        private Rig() throws Exception {
            Path home = Files.createTempDirectory("pi-settings-action-home");
            System.setProperty("user.home", home.toString());
            session = AgentSession.create(ArgsParser.parse(new String[] {
                "--session-dir", Files.createTempDirectory("pi-settings-action-session").toString() }));
            handler = new SettingsActionHandler(session, new SettingsActionHandler.Host() { });
        }

        @Override
        public void close() {
            session.close();
            System.setProperty("user.home", oldHome);
        }
    }
}
